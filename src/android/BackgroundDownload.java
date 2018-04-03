/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
 */
package org.apache.cordova.backgroundDownload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.util.SparseArray;

/**
 * Based on DownloadManager which is intended to be used for long-running HTTP downloads. Support of Android 2.3. (API 9) and later
 * http://developer.android.com/reference/android/app/DownloadManager.html TODO: concurrent downloads support
 */

public class BackgroundDownload extends CordovaPlugin {

    private static final String TAG = "BackgroundDownload";

    private static final long DOWNLOAD_ID_UNDEFINED = -1;
    private static final long DOWNLOAD_PROGRESS_UPDATE_TIMEOUT = 1000;

    private static class PermissionsRequest {

        private final JSONArray rawArgs;
        private final CallbackContext callbackContext;

        private PermissionsRequest(JSONArray rawArgs, CallbackContext callbackContext) {
            this.rawArgs = rawArgs;
            this.callbackContext = callbackContext;
        }
    }

    private static class Download {

        private String filePath;
        private String tempFilePath;
        private String uriString;
        private CallbackContext callbackContext; // The callback context from which we were invoked.
        private long downloadId = DOWNLOAD_ID_UNDEFINED;
        private Timer timerProgressUpdate = null;

        public Download(String uriString, String filePath,
                CallbackContext callbackContext) {
            this.setUriString(uriString);
            this.setFilePath(filePath);
            this.setTempFilePath(Uri.fromFile(new File(android.os.Environment.getExternalStorageDirectory().getPath(),
                    Uri.parse(filePath).getLastPathSegment() + "." + System.currentTimeMillis())).toString());
            this.setCallbackContext(callbackContext);
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public String getUriString() {
            return uriString;
        }

        public void setUriString(String uriString) {
            this.uriString = uriString;
        }

        public String getTempFilePath() {
            return tempFilePath;
        }

        public void setTempFilePath(String tempFilePath) {
            this.tempFilePath = tempFilePath;
        }

        public CallbackContext getCallbackContext() {
            return callbackContext;
        }

        public void setCallbackContext(CallbackContext callbackContext) {
            this.callbackContext = callbackContext;
        }

        public long getDownloadId() {
            return downloadId;
        }

        public void setDownloadId(long downloadId) {
            this.downloadId = downloadId;
        }

        public Timer getTimerProgressUpdate() {
            return timerProgressUpdate;
        }

        public void setTimerProgressUpdate(Timer TimerProgressUpdate) {
            this.timerProgressUpdate = TimerProgressUpdate;
        };
    }

    private SparseArray<PermissionsRequest> permissionRequests;

    private HashMap<String, Download> activeDownloads = new HashMap<>();

    private DownloadManager getDownloadManager() {
        return (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        permissionRequests = new SparseArray<>();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        try {
            if (action.equals("startAsync")) {
                startAsync(args, callbackContext);
                return true;
            }
            if (action.equals("stop")) {
                stop(args, callbackContext);
                return true;
            }
            return false; // invalid action
        } catch (Exception ex) {
            callbackContext.error(ex.getMessage());
        }
        return true;
    }

    private void startAsync(JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (!checkPermissions(args, callbackContext)) {
            return;
        }

        if (activeDownloads.size() == 0) {
            // required to receive notification when download is completed
            cordova.getActivity().registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        Download curDownload = new Download(args.get(0).toString(), args.get(1).toString(), callbackContext);

        if (activeDownloads.containsKey(curDownload.getUriString())) {
            return;
        }

        activeDownloads.put(curDownload.getUriString(), curDownload);
        Uri source = Uri.parse(curDownload.getUriString());
        // Uri destination = Uri.parse(this.getTemporaryFilePath());

        // attempt to attach to active download for this file (download started and we close/open the app)
        curDownload.setDownloadId(findActiveDownload(curDownload.getUriString()));

        if (curDownload.getDownloadId() == DOWNLOAD_ID_UNDEFINED) {
            try {
                // make sure file does not exist, in other case DownloadManager will fail
                File targetFile = new File(Uri.parse(curDownload.getTempFilePath()).getPath());
                targetFile.delete();

                DownloadManager mgr = getDownloadManager();
                DownloadManager.Request request = new DownloadManager.Request(source);
                request.setTitle("org.apache.cordova.backgroundDownload plugin");
                request.setVisibleInDownloadsUi(false);

                // hide notification. Not compatible with current android api.
                // request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);

                // we use default settings for roaming and network type
                // request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
                // request.setAllowedOverRoaming(false);

                request.setDestinationUri(Uri.parse(curDownload.getTempFilePath()));

                curDownload.setDownloadId(mgr.enqueue(request));
            } catch (Exception ex) {
                cleanUp(curDownload);
                callbackContext.error(ex.getMessage());
                return;
            }
        } else if (checkDownloadCompleted(curDownload.getDownloadId())) {
            return;
        }

        // custom logic to track file download progress
        startProgressTracking(curDownload);
    }

    private void startProgressTracking(final Download curDownload) {
        // already started
        if (curDownload.getTimerProgressUpdate() != null) {
            return;
        }
        final DownloadManager mgr = getDownloadManager();

        curDownload.setTimerProgressUpdate(new Timer());
        curDownload.getTimerProgressUpdate().schedule(new TimerTask() {
            @Override
            public void run() {
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(curDownload.getDownloadId());
                Cursor cursor = mgr.query(q);
                try {
                    if (!cursor.moveToFirst()) {
                        cordova.getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                cleanUp(curDownload);
                                curDownload.getCallbackContext().error("cancelled or terminated");
                            }
                        });
                        return;
                    }

                    final int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    final int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));

                    PluginResult progressUpdate;
                    JSONObject obj;
                    switch (status) {
                        case DownloadManager.STATUS_FAILED:
                            cordova.getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    cleanUp(curDownload);
                                    reportError(curDownload.getCallbackContext(), reason);
                                }
                            });
                            return;
                        case DownloadManager.STATUS_RUNNING:
                        case DownloadManager.STATUS_SUCCESSFUL:
                            long bytesDownloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            long bytesTotal = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            JSONObject jsonProgress = new JSONObject();
                            jsonProgress.put("bytesReceived", bytesDownloaded);
                            jsonProgress.put("totalBytesToReceive", bytesTotal);
                            obj = new JSONObject();
                            obj.put("progress", jsonProgress);
                            break;
                        case DownloadManager.STATUS_PAUSED:
                            JSONObject pauseMessage = new JSONObject();
                            pauseMessage.put("message", "Download paused with reason " + reason);
                            obj = new JSONObject();
                            obj.put("progress", pauseMessage);
                            break;
                        case DownloadManager.STATUS_PENDING:
                            JSONObject pendingMessage = new JSONObject();
                            pendingMessage.put("message", "Download pending with reason " + reason);
                            obj = new JSONObject();
                            obj.put("progress", pendingMessage);
                            break;
                        default:
                            curDownload.getCallbackContext().error("Unknown download state " + status);
                            return;
                    }

                    progressUpdate = new PluginResult(PluginResult.Status.OK, obj);
                    progressUpdate.setKeepCallback(true);
                    curDownload.getCallbackContext().sendPluginResult(progressUpdate);
                } catch (JSONException e){
                    e.printStackTrace();
                } finally {
                    cursor.close();
                }
            }
        }, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT);
    }

    private void cleanUp(Download curDownload) {

        if (curDownload.getTimerProgressUpdate() != null) {
            curDownload.getTimerProgressUpdate().cancel();
        }

        if (curDownload.getDownloadId() != DOWNLOAD_ID_UNDEFINED) {
            getDownloadManager().remove(curDownload.getDownloadId());
        }

        activeDownloads.remove(curDownload.getUriString());

        if (activeDownloads.size() == 0) {
            try {
                cordova.getActivity().unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                // this is fine, receiver was not registered
            }
        }
    }

    private Download getDownloadEntryById(Long downloadId) {
        for (Download download : activeDownloads.values()) {
            if (download.getDownloadId() == downloadId) {
                return download;
            }
        }
        return null;
    }

    private String getUserFriendlyReason(int reason) {
        String failedReason = "";
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                failedReason = "ERROR_CANNOT_RESUME";
                break;
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                failedReason = "ERROR_DEVICE_NOT_FOUND";
                break;
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                failedReason = "ERROR_FILE_ALREADY_EXISTS";
                break;
            case DownloadManager.ERROR_FILE_ERROR:
                failedReason = "ERROR_FILE_ERROR";
                break;
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                failedReason = "ERROR_HTTP_DATA_ERROR";
                break;
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                failedReason = "ERROR_INSUFFICIENT_SPACE";
                break;
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                failedReason = "ERROR_TOO_MANY_REDIRECTS";
                break;
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                failedReason = "ERROR_UNHANDLED_HTTP_CODE";
                break;
            case DownloadManager.ERROR_UNKNOWN:
                failedReason = "ERROR_UNKNOWN";
                break;
            case HttpURLConnection.HTTP_BAD_REQUEST:
                failedReason = "BAD_REQUEST";
                break;
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                failedReason = "UNAUTHORIZED";
                break;
            case HttpURLConnection.HTTP_FORBIDDEN:
                failedReason = "FORBIDDEN";
                break;
            case HttpURLConnection.HTTP_NOT_FOUND:
                failedReason = "NOT_FOUND";
                break;
            case HttpURLConnection.HTTP_INTERNAL_ERROR:
                failedReason = "INTERNAL_SERVER_ERROR";
        }

        return failedReason;
    }

    private void stop(JSONArray args, CallbackContext callbackContext) throws JSONException {

        Download curDownload = activeDownloads.get(args.get(0).toString());
        if (curDownload == null) {
            callbackContext.error("download request not found");
            return;
        }

        cleanUp(curDownload);
        callbackContext.success();
    }

    private long findActiveDownload(String uri) {
        long downloadId = DOWNLOAD_ID_UNDEFINED;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING    | DownloadManager.STATUS_SUCCESSFUL);
        Cursor cur = getDownloadManager().query(query);
        int idxId = cur.getColumnIndex(DownloadManager.COLUMN_ID);
        int idxUri = cur.getColumnIndex(DownloadManager.COLUMN_URI);
        for (cur.moveToFirst(); !cur.isAfterLast(); cur.moveToNext()) {
            if (uri.equals(cur.getString(idxUri))) {
                downloadId = cur.getLong(idxId);
                break;
            }
        }
        cur.close();

        return downloadId;
    }

    private Boolean checkDownloadCompleted(long id) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        Cursor cur = getDownloadManager().query(query);
        int idxStatus = cur.getColumnIndex(DownloadManager.COLUMN_STATUS);
        int idxURI = cur.getColumnIndex(DownloadManager.COLUMN_URI);
        int idxLocalUri = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);

        try {
            if (cur.moveToFirst()) {
                int status = cur.getInt(idxStatus);
                String uri = cur.getString(idxURI);
                String existingFile = cur.getString(idxLocalUri);
                Download curDownload = activeDownloads.get(uri);
                curDownload.setTempFilePath(existingFile);
                if (status == DownloadManager.STATUS_SUCCESSFUL) { // TODO review what else we can have here
                    copyTempFileToActualFile(curDownload);
                    cleanUp(curDownload);
                    return true;
                }
            }
        } finally {
            cur.close();
        }

        return false;
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

            Download curDownload = getDownloadEntryById(downloadId);
            if (curDownload == null) {
                return;
            }

            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            Cursor cursor = getDownloadManager().query(query);
            try {
                if (cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        copyTempFileToActualFile(curDownload);
                        return;
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));
                        reportError(curDownload.getCallbackContext(), reason);
                    }
                }
                curDownload.getCallbackContext().error("cancelled or terminated");
            } catch (Exception ex) {
                curDownload.getCallbackContext().error(ex.getMessage());
            } finally {
                cursor.close();
                cleanUp(curDownload);
            }
        }
    };

    private void copyTempFileToActualFile(Download curDownload) {
        File sourceFile = new File(Uri.parse(curDownload.getTempFilePath()).getPath());
        File destFile = new File(Uri.parse(curDownload.getFilePath()).getPath());

        try {
            copyFile(sourceFile, destFile);
            curDownload.getCallbackContext().success();
        } catch (IOException e) {
            curDownload.getCallbackContext().error("Cannot copy from temporary path to actual path");
            Log.e(TAG, String.format("Error occurred while copying the file. Source: '%s'(%s), dest: '%s'",
                    curDownload.getTempFilePath(), sourceFile.exists(), curDownload.getFilePath()), e);
        }
    }

    private void copyFile(File src, File dest) throws IOException {
        FileChannel inChannel = new FileInputStream(src).getChannel();
        FileChannel outChannel = new FileOutputStream(dest).getChannel();
        try {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        } finally {
            if (inChannel != null) {
                inChannel.close();
            }
            if (outChannel != null) {
                outChannel.close();
            }
        }
    }

    private void reportError(CallbackContext callbackContext, int errorCode) {
        String reasonMsg = getUserFriendlyReason(errorCode);
        if ("".equals(reasonMsg))
            reasonMsg = String.format(Locale.getDefault(), "Download operation failed with reason: %d", errorCode);

        callbackContext.error(reasonMsg);
    }

    private boolean checkPermissions(JSONArray args, CallbackContext callbackContext) {
        if (!PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            permissionRequests.put(permissionRequests.size(), new PermissionsRequest(args, callbackContext));
            PermissionHelper.requestPermission(this, permissionRequests.size() - 1, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return false;
        }

        return true;
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        final PermissionsRequest permissionsRequest = permissionRequests.get(requestCode);
        permissionRequests.remove(requestCode);
        if (permissionsRequest == null) {
            return;
        }

        if (grantResults.length < 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            permissionsRequest.callbackContext.error("PERMISSION_DENIED");
            return;
        }

        try {
            startAsync(permissionsRequest.rawArgs, permissionsRequest.callbackContext);
        } catch (JSONException ex) {
            permissionsRequest.callbackContext.error(ex.getMessage());
        }
    }
}
