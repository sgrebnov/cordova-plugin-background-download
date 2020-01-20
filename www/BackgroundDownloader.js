/*
 *
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

var DownloadOperation = require('./DownloadOperation');

/**
 * Initializes a new instance of BackgroundDownloader object.
 * Used to configure downloads prior to the actual creation of the download operation using CreateDownload.
 *
 */
var BackgroundDownloader = function() {

};

/**
 * Initializes a DownloadOperation object that contains the specified Uri and the file that the response is written to.
 *
 * @param {JSON} downloadConfiguration The configuration for background download task
 * Example:
 * {
 *   targetFile: <Path to local file system to store the downloaded file>,
 *   downloadURL: <url to download from>,
 *   requestHeaders: <request headers to be added on the request to download file>
 *   sessionId: <id to assign to the url session to create request>
 *   downloadDelay: <download delay in seconds>
 * }
 */
BackgroundDownloader.prototype.createDownload = function(downloadConfiguration) {
    return new DownloadOperation(downloadConfiguration);
};

module.exports = BackgroundDownloader;