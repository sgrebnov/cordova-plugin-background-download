/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
*/

var exec = require('cordova/exec'),
    q = require('./q');

/**
 * Performs recurrent asynchronous background download operations on a regular basis.
*/
var DownloadOperation = function (uri, resultFile) {

    if (uri == null || resultFile == null) {
        throw new Error("missing or invalid argument");
    }
    
    this.uri = uri;
    this.resultFile = resultFile;
};

/**
 * Starts download operations.
*/
DownloadOperation.prototype.startAsync = function() {

    var deferred = q.defer(),
        me = this,
        successCallback = function(result) {

            // success callback is used to both report operation progress and 
            // as operation completeness handler
            
            if (result && typeof result.progress != 'undefined') {
                deferred.notify(result.progress);
            } else {
                deferred.resolve(result);
            }
        },
        errorCallback = function(err) {
            deferred.reject(err);
        };

    exec(successCallback, errorCallback, "BackgroundDownload", "startAsync", [this.uri, this.resultFile.fullPath]);

    // Cancel support via custom cancel function:
    // Q.js does not provide such functionality
    // https://github.com/angular/angular.js/pull/2452
    deferred.promise.cancel = function() {
        me.stop();
    };

    return deferred.promise;
};

/**
 * Stops download operations.
*/
DownloadOperation.prototype.stop = function() {

    exec(null, null, "BackgroundDownload", "stop", [this.uri]);

};

module.exports = DownloadOperation;