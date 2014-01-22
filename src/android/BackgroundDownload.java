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
import java.util.Timer;
import java.util.TimerTask;

import org.apache.cordova.CallbackContext;
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

/**
 * Based on DownloadManager which is intended to be used for long-running HTTP downloads. Support of Android 2.3. (API 9) and later
 * http://developer.android.com/reference/android/app/DownloadManager.html TODO: concurrent downloads support
 */
public class BackgroundDownload extends CordovaPlugin {

    private static final long DOWNLOAD_ID_UNDEFINED = -1;
    private static final long DOWNLOAD_PROGRESS_UPDATE_TIMEOUT = 1000;
    private static final String TEMP_DOWNLOAD_FILE_EXTENSION = ".temp";

    private String _filePath;
    private String _tempFilePath;
    private String _uriString;
    private CallbackContext _callbackContext; // The callback context from which we were invoked.
    private CallbackContext _callbackContextDownloadStart; // The callback context from which we started file download command.
    private long _activeDownloadId = DOWNLOAD_ID_UNDEFINED;
    private Timer _timerProgressUpdate = null;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this._callbackContext = callbackContext;
        try {

            if (action.equals("startAsync")) {
                startAsync(args);
                return true;
            }
            if (action.equals("stop")) {
                stop(args);
                return true;
            }
            return false; // invalid action
        } catch (Exception ex) {
            this._callbackContext.error(ex.getMessage());
        }

        return true;
    }

    private void startAsync(JSONArray args) throws JSONException {
        this._uriString = args.get(0).toString();
        this._filePath = args.get(1).toString();
        this._tempFilePath = _filePath + TEMP_DOWNLOAD_FILE_EXTENSION;
        this._callbackContextDownloadStart = this._callbackContext;

        Uri source = Uri.parse(this._uriString);
        // Uri destination = Uri.parse(this.getTemporaryFilePath());

        // attempt to attach to active download for this file (download started and we close/open the app)
        this._activeDownloadId = findActiveDownload(this._uriString);

        // new file download request
        if (this._activeDownloadId == DOWNLOAD_ID_UNDEFINED) {
            // make sure file does not exist, in other case DownloadManager will fail
            File targetFile = new File(Uri.parse(_tempFilePath).getPath());
            targetFile.delete();

            DownloadManager mgr = (DownloadManager) this.cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(source);
            request.setTitle("org.apache.cordova.backgroundDownload plugin");
            request.setVisibleInDownloadsUi(false);

            // hide notification. Not compatible with current android api.
            // request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);

            // we use default settings for roaming and network type
            // request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
            // request.setAllowedOverRoaming(false);

            request.setDestinationUri(Uri.parse(_tempFilePath));

            this._activeDownloadId = mgr.enqueue(request);
        } else if (checkDownloadCompleted(this._activeDownloadId)) {
            return;
        }

        // required to receive notification when download is completed
        cordova.getActivity().registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        // custom logic to track file download progress
        StartProgressTracking();
    }

    private void StartProgressTracking() {
        // already started
        if (_timerProgressUpdate != null) {
            return;
        }
        final DownloadManager mgr = (DownloadManager) this.cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);

        _timerProgressUpdate = new Timer();
        _timerProgressUpdate.schedule(new TimerTask() {
            @Override
            public void run() {
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(_activeDownloadId);
                Cursor cursor = mgr.query(q);
                if (cursor.moveToFirst()) {
                    long bytes_downloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (bytes_total != -1) {
                        long progress = (bytes_downloaded * 100 / bytes_total);
                        try {
                            JSONObject obj = new JSONObject();
                            obj.put("progress", progress);
                            PluginResult progressUpdate = new PluginResult(PluginResult.Status.OK, obj);
                            progressUpdate.setKeepCallback(true);
                            _callbackContextDownloadStart.sendPluginResult(progressUpdate);
                        } catch (JSONException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                }
                cursor.close();
            }

        }, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT);
    }

    private void CleanUp() {
        try {
            cordova.getActivity().unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            // this is fine, receiver was not registered
        }

        if (_timerProgressUpdate != null) {
            _timerProgressUpdate.cancel();
            _timerProgressUpdate = null;
        }

        if (_activeDownloadId != DOWNLOAD_ID_UNDEFINED) {
            DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            mgr.remove(_activeDownloadId);
            _activeDownloadId = DOWNLOAD_ID_UNDEFINED;
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

    private void stop(JSONArray args) {
        if (_activeDownloadId == DOWNLOAD_ID_UNDEFINED) {
            this._callbackContext.error("download requst not found");
            return;
        }

        DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        mgr.remove(_activeDownloadId);
        _callbackContext.success();
    }

    private long findActiveDownload(String uri) {

        DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);

        long downloadId = DOWNLOAD_ID_UNDEFINED;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_SUCCESSFUL);
        Cursor cur = mgr.query(query);
        // int idxFileName = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
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
        // int idxReason = cur.getColumnIndex(DownloadManager.COLUMN_REASON);
        // int idxFilePath = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
        if (cur.moveToFirst()) {
            int status = cur.getInt(idxStatus);
            // String fileLocation = cur.getString(idxFilePath);
            if (status == DownloadManager.STATUS_SUCCESSFUL) { // TODO review what else we can have here
                copyTempFileToActualFile();
                CleanUp();
                return true;
            }
        }
        cur.close();

        return false;
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            try {
                long receivedID = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                DownloadManager mgr = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(receivedID);
                Cursor cur = mgr.query(query);
                int idxStatus = cur.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int idxReason = cur.getColumnIndex(DownloadManager.COLUMN_REASON);
                if (cur.moveToFirst()) {
                    int status = cur.getInt(idxStatus);
                    int reason = cur.getInt(idxReason);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        copyTempFileToActualFile();
                    } else {
                        _callbackContextDownloadStart.error("Download operation failed with status " + status + " and reason: " + getUserFriendlyReason(reason));
                    }
                } else {
                    _callbackContextDownloadStart.error("cancelled or terminated");
                }
                cur.close();
            } catch (Exception ex) {
                _callbackContextDownloadStart.error(ex.getMessage());
            } finally {
                CleanUp();
            }
        }
    };

    public void copyTempFileToActualFile() {
        File sourceFile = new File(Uri.parse(_tempFilePath).getPath());
        File destFile = new File(Uri.parse(_filePath).getPath());
        if (sourceFile.renameTo(destFile)) {
            _callbackContextDownloadStart.success();
        } else {
            _callbackContextDownloadStart.error("Cannot copy from temporary path to actual path");
        }
    }
}