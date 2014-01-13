﻿module.exports = {
    downloadOperationPromise: null, // TODO concurrent operations support
    startAsync: function (success, fail, args) {
        try {
            var uri = args[0],
                resultFilePath = args[1];

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

            Windows.Storage.StorageFile.getFileFromPathAsync(resultFilePath).done(
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