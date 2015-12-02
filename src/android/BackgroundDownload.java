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
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

/**
 * Based on DownloadManager which is intended to be used for long-running HTTP downloads. Support of Android 2.3. (API 9) and later
 * http://developer.android.com/reference/android/app/DownloadManager.html TODO: concurrent downloads support
 */

public class BackgroundDownload extends CordovaPlugin {
    private static final long DOWNLOAD_ID_UNDEFINED = -1;
    private static final String TEMP_DOWNLOAD_FILE_EXTENSION = ".temp";
    private static final long DOWNLOAD_PROGRESS_UPDATE_TIMEOUT = 1000;
    private static final String INTERNAL_APP_PATH = "/data/data/com.cld.ed";
    private static final String TEMP_DOWNLOAD_PATH = Environment.getExternalStorageDirectory().toString() +"/com.cld.ed";

    protected class Download {

        private String filePath;
        private String tempFilePath;
        private String uriString;
        private CallbackContext callbackContext; // The callback context from which we were invoked.
        private CallbackContext callbackContextDownloadStart; // The callback context from which we started file download command.
        private long downloadId = DOWNLOAD_ID_UNDEFINED;
        private Timer timerProgressUpdate = null;

        public Download(String uriString, String filePath,
                        CallbackContext callbackContext) {
            this.setUriString(uriString);
            this.setFilePath(filePath);
            this.setTempFilePath(filePath + TEMP_DOWNLOAD_FILE_EXTENSION);
            this.setCallbackContext(callbackContext);
            this.setCallbackContextDownloadStart(callbackContext);
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
            tempFilePath = tempFilePath.replace(INTERNAL_APP_PATH, TEMP_DOWNLOAD_PATH);
            this.tempFilePath = tempFilePath;
        }

        public CallbackContext getCallbackContext() {
            return callbackContext;
        }

        public void setCallbackContext(CallbackContext callbackContext) {
            this.callbackContext = callbackContext;
        }

        public CallbackContext getCallbackContextDownloadStart() {
            return callbackContextDownloadStart;
        }

        public void setCallbackContextDownloadStart(
                CallbackContext callbackContextDownloadStart) {
            this.callbackContextDownloadStart = callbackContextDownloadStart;
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

    HashMap<String, Download> activDownloads = new HashMap<String, Download>();

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        try {
            if (action.equals("startAsync")) {
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        try {
                            startAsync(args, callbackContext);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                });

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

    private boolean prepareDirectories(Download download) {
        String parentDirPath = download.getTempFilePath().replace("file://", "");
        File fullPath = new File(parentDirPath);
        File dir = new File(fullPath.getParent());

        if (! dir.exists()) {
            if (dir.mkdirs()) {
                return true;
            } else {
                Log.e("prepareDirectories", "Failed to prepare directory for " + download.getTempFilePath());
                return false;
            }
        } else {
            return false;
        }
    }

    private void startAsync(JSONArray args, CallbackContext callbackContext) throws JSONException {

        if (activDownloads.size() == 0) {
            // required to receive notification when download is completed
            Log.d("BackgroundDownload", "Registering the receiver");
            cordova.getActivity().registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        Download curDownload = new Download(args.get(0).toString(), args.get(1).toString(), callbackContext);

        if (activDownloads.containsKey(curDownload.getUriString())) {
            return;
        }

        activDownloads.put(curDownload.getUriString(), curDownload);
        curDownload.setDownloadId(findActiveDownload(curDownload.getUriString()));

        if (curDownload.getDownloadId() == DOWNLOAD_ID_UNDEFINED) {
            // make sure file does not exist, in other case DownloadManager will fail
            File targetFile = new File(Uri.parse(curDownload.getTempFilePath()).getPath());
            targetFile.delete();

            // Prepare the parent directories of the file
            prepareDirectories(curDownload);

            DownloadManager mgr = (DownloadManager) this.cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(curDownload.getUriString()));
            request.setTitle("org.apache.cordova.backgroundDownload plugin");
            request.setVisibleInDownloadsUi(false);
            if (Build.VERSION.SDK_INT >= 11) {
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
            }

            request.setDestinationUri(Uri.parse(curDownload.getTempFilePath()));
            Log.d("BackgroundDownload", "downloading");
            curDownload.setDownloadId(mgr.enqueue(request));
        } else if (checkDownloadCompleted(curDownload.getDownloadId())){
            callbackContext.success();
        }

        StartProgressTracking(curDownload);
    }

    private void StartProgressTracking(final Download curDownload) {
        // already started
        if (curDownload.getTimerProgressUpdate() != null) {
            return;
        }
        final DownloadManager mgr = (DownloadManager) this.cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);

        curDownload.setTimerProgressUpdate(new Timer());
        curDownload.getTimerProgressUpdate().schedule(new TimerTask() {
            @Override
            public void run() {
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(curDownload.getDownloadId());
                Cursor cursor = mgr.query(q);
                if (cursor.moveToFirst()) {
                    long bytesDownloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long bytesTotal = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (bytesTotal != -1) {
                        Log.d("BackgroundDownload", "DOWNLOAD STARTED for " + curDownload.getDownloadId());
                        try {
                            JSONObject jsonProgress = new JSONObject();
                            jsonProgress.put("bytesReceived", bytesDownloaded);
                            jsonProgress.put("totalBytesToReceive", bytesTotal);
                            JSONObject obj = new JSONObject();
                            obj.put("progress", jsonProgress);
                            PluginResult progressUpdate = new PluginResult(PluginResult.Status.OK, obj);
                            progressUpdate.setKeepCallback(true);
                            curDownload.getCallbackContextDownloadStart().sendPluginResult(progressUpdate);
                        } catch (JSONException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    } else {
                        long status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        long reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));
                        Log.d("BackgroundDownload", "download not started for " + curDownload.getTempFilePath()
                                + " (status " + status + ") (reason " + reason + ")");
                        if (reason == 404) {
                            try {
                                cordova.getActivity().unregisterReceiver(receiver);
                            } catch (Exception e) {
                                // Noop
                            }
                        }
                    }
                }
                cursor.close();
            }
        }, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT);
    }

    private void CleanUp(Download curDownload) {
        if (curDownload == null) {
            return;
        }

        if (curDownload.getTimerProgressUpdate() != null) {
            curDownload.getTimerProgressUpdate().cancel();
        }

        if (curDownload.getDownloadId() != DOWNLOAD_ID_UNDEFINED) {
            DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            mgr.remove(curDownload.getDownloadId());
        }
        activDownloads.remove(curDownload.getUriString());

        if (activDownloads.size() == 0) {
            try {
                cordova.getActivity().unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                // this is fine, receiver was not registered
            }
        }

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
        }

        return failedReason;
    }

    private void stop(JSONArray args, CallbackContext callbackContext) throws JSONException {

        Download curDownload = activDownloads.get(args.get(0).toString());
        if (curDownload == null) {
            callbackContext.error("download requst not found");
            return;
        }

        DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        mgr.remove(curDownload.getDownloadId());
        callbackContext.success();
    }

    private long findActiveDownload(String uri) {
        DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);

        long downloadId = DOWNLOAD_ID_UNDEFINED;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_SUCCESSFUL);
        Cursor cur = mgr.query(query);
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
        DownloadManager mgr = (DownloadManager) this.cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        Cursor cur = mgr.query(query);
        int idxStatus = cur.getColumnIndex(DownloadManager.COLUMN_STATUS);
        int idxURI = cur.getColumnIndex(DownloadManager.COLUMN_URI);

        if (cur.moveToFirst()) {
            int status = cur.getInt(idxStatus);
            String uri = cur.getString(idxURI);
            Download curDownload = activDownloads.get(uri);
            if (status == DownloadManager.STATUS_SUCCESSFUL) { // TODO review what else we can have here
                copyTempFileToActualFile(curDownload);
                CleanUp(curDownload);
                return true;
            }
        }
        cur.close();

        return false;
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.d("BackgroundDownload", "recevied an intent " + intent.getAction());
            DownloadManager mgr = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            Cursor cursor = mgr.query(query);
            int idxURI = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
            cursor.moveToFirst();
            if (cursor.getCount() == 0) return;
            String uri = cursor.getString(idxURI);

            Download curDownload = activDownloads.get(uri);

            try {
                long receivedID = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                query.setFilterById(receivedID);
                int idxStatus = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int idxReason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);

                if (cursor.moveToFirst()) {
                    int status = cursor.getInt(idxStatus);
                    int reason = cursor.getInt(idxReason);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        copyTempFileToActualFile(curDownload);
                        curDownload.getCallbackContextDownloadStart().success();
                    } else {
                        curDownload.getCallbackContextDownloadStart().error("Download operation failed with status " + status + " and reason: "    + getUserFriendlyReason(reason));
                    }
                } else {
                    curDownload.getCallbackContextDownloadStart().error("cancelled or terminated");
                }
                cursor.close();
            } catch (Exception ex) {
                curDownload.getCallbackContextDownloadStart().error(ex.getMessage());
            } finally {
                CleanUp(curDownload);
            }
        }
    };

    public void copyTempFileToActualFile(Download curDownload) {
        File sourceFile = new File(Uri.parse(curDownload.getTempFilePath()).getPath());
        File destFile = new File(Uri.parse(curDownload.getFilePath()).getPath());

        if (! sourceFile.exists()) return;

        try {
            copyFile(sourceFile, destFile);

            // Remove the temporary download directory from the external storage
            // File tmpDir = new File(TEMP_DOWNLOAD_PATH);
            // deleteRecursive(tmpDir);

            // We rename tmpDir before deleting it to prevent Android filesystem from keeping
            // a reference to the old directory and locking it after deletion.
            // http://stackoverflow.com/questions/11539657/open-failed-ebusy-device-or-resource-busy
            // Not used at the moment because doing so holds up the download in Android 6
//            final File renamedTmpDir = new File(tmpDir.getAbsolutePath() + System.currentTimeMillis());
//            tmpDir.renameTo(renamedTmpDir);
//            deleteRecursive(renamedTmpDir);

        } catch (IOException e) {
            curDownload.getCallbackContextDownloadStart().error("Cannot copy from temporary path to actual path");
            Log.e("BackgroundDownload", "Error occurred while copying the file");
            e.printStackTrace();
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

    private boolean deleteRecursive(File path) {
        if (path.isDirectory()) {
            for (File file : path.listFiles()) {
                if (!deleteRecursive(file)) {
                    return false;
                }
            }
        }
        return path.delete();
    }
}
