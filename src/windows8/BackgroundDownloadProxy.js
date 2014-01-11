﻿module.exports = {
    downloadOperationPromise: null, // TODO concurrent operations support
    startAsync: function (success, fail, args) {
        try {
            var download = args[0],
                resultFile = download.resultFile,
                uri = Windows.Foundation.Uri(download.uri);

            var completeHandler = function() {
                success();
            };
            var errorHandler = function(err) {
                fail(err);
            };
            var progressHandler = function (operation) {

                var progress = 100 * operation.progress.bytesReceived / operation.progress.totalBytesToReceive;

                success({
                    progress: progress,
                    keepCallback: true
                });
            };

            Windows.Storage.StorageFile.getFileFromPathAsync(resultFile.fullPath).done(
                function (file) {
                    var downloadOperation = Windows.Networking.BackgroundTransfer.BackgroundDownloader().createDownload(uri, file);
                    downloadOperationPromise = downloadOperation.startAsync().then(completeHandler, errorHandler, progressHandler);
                }, errorHandler);
        } catch(ex) {
            fail(ex);
        }

    },
    stop: function (success, fail, args) {
        try {
            downloadOperationPromise && downloadOperationPromise.cancel();
            success();
        } catch(ex) {
            fail(ex);
        }
    }
};

require("cordova/windows8/commandProxy").add("BackgroundDownload", module.exports);