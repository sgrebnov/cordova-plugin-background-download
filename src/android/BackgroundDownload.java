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

import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import android.os.Build;
import android.webkit.WebSettings.PluginState;

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
 * Based on DownloadManager which is intended to be used for long-running HTTP
 * download. Suppoer of Android 2.3. (API 9)
 * http://developer.android.com/reference/android/app/DownloadManager.html
 * 
 * TODO track completeness via BroadcastReceiver instead of timer
 * http://sandersdenardi.com/using-the-android-downloadmanager/
 */
public class BackgroundDownload extends CordovaPlugin {

    private static final long DOWNLOAD_ID_UNDEFINED = -1;
    private static final long DOWNLOAD_PROGRESS_UPDATE_TIMEOUT = 1000;

    private CallbackContext callbackContext; // The callback context from which we were invoked.
    private CallbackContext initialCallbackContext; // The callback context from which we started file download command.
    private Timer progressUpdateTimer = null;
    
    
    private String targetFile;
    private String uri;
    private long downloadId = DOWNLOAD_ID_UNDEFINED;
    
    @Override
    public boolean execute(String action, JSONArray args,
            CallbackContext callbackContext) throws JSONException {

        this.callbackContext = callbackContext;
        try {
            
            if (action.equals("startAsync")) {
                startAsync(args);
                return true;
            } 

            if (action.equals("stop")) {
               stop(args);
               return true;
            }
            
            // invalid action
            return false;
            
        } catch (Exception ex) {
            this.callbackContext.error(ex.getMessage());
        }

        return true;
    }

    private void startAsync(JSONArray args) throws JSONException {
        this.uri = (String) args.get(0);
        this.targetFile = (String) args.get(1);
        this.initialCallbackContext = this.callbackContext;
        
        
        Uri source = Uri.parse(this.uri);
        Uri destination = Uri.parse(this.targetFile);
        
        final DownloadManager mgr = (DownloadManager) this.cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(source);
        request.setTitle("org.apache.cordova.backgroundDownload plugin");
        
        request.setDestinationUri(destination);
        
        // use default settings for roamin and network type
        //Restrict the types of networks over which this download may proceed.
        //request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
        //Set whether this download may proceed over a roaming connection.
        //request.setAllowedOverRoaming(false);
        
       cordova.getActivity().registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

       this.downloadId = mgr.enqueue(request);
       
       progressUpdateTimer = new Timer();
       progressUpdateTimer.schedule(new TimerTask() {          
           @Override
               public void run() {
               DownloadManager.Query q = new DownloadManager.Query();
                   q.setFilterById(downloadId);
                   Cursor cursor = mgr.query(q);
                   if (cursor.moveToFirst()) {
                       int bytes_downloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                       int bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                       if (bytes_total != -1) {
                           int progress = (bytes_downloaded * 100 / bytes_total);                           
                           try {
                               JSONObject obj = new JSONObject();
                               obj.put("progress", progress);
                               PluginResult progressUpdate = new PluginResult(PluginResult.Status.OK, obj);
                               progressUpdate.setKeepCallback(true);
                               initialCallbackContext.sendPluginResult(progressUpdate);
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
    
    private void CleanUp () {
        cordova.getActivity().unregisterReceiver(receiver);
        
        if (progressUpdateTimer != null) {
            progressUpdateTimer.cancel();
            progressUpdateTimer = null;
        }
        
        if (downloadId != DOWNLOAD_ID_UNDEFINED) {
            DownloadManager mgr = (DownloadManager)cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            mgr.remove(downloadId);
            downloadId = DOWNLOAD_ID_UNDEFINED;
        }
    }
    
    private String getUserFriendlyReason(int reason) {
        String failedReason = "";
        switch(reason){
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
       if(downloadId == DOWNLOAD_ID_UNDEFINED) {
           this.callbackContext.error("donwload requst not found");
           return;
       } 
       
       DownloadManager mgr = (DownloadManager)cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
       mgr.remove(downloadId);
       callbackContext.success();                 
    }

    private Boolean isDownloading() {

        DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        
        boolean isDownloading = false;
        
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_PAUSED
                | DownloadManager.STATUS_PENDING
                | DownloadManager.STATUS_RUNNING
                | DownloadManager.STATUS_SUCCESSFUL);
        Cursor cur = mgr.query(query);
        int col = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);
        for (cur.moveToFirst(); !cur.isAfterLast(); cur.moveToNext()) {
            isDownloading = (this.targetFile == cur.getString(col));
        }
        cur.close();

        return isDownloading;
    }
    private BroadcastReceiver receiver  = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            long receivedID = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            DownloadManager mgr = (DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(receivedID);
            Cursor cur = mgr.query(query);
            int idxStatus = cur.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int idxReason = cur.getColumnIndex(DownloadManager.COLUMN_REASON);                
            if(cur.moveToFirst()) 
            {
                int status = cur.getInt(idxStatus);
                int reason = cur.getInt(idxReason);
                if( status == DownloadManager.STATUS_SUCCESSFUL){
                    initialCallbackContext.success();
                } else {
                    initialCallbackContext.error("Download operation failed with status " + status + " and reason: " + getUserFriendlyReason(reason));
                }
            } else {
                initialCallbackContext.error("cancelled or terminated");
            }
            cur.close();
            
            CleanUp();
        }
    };
}