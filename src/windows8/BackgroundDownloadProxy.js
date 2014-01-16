﻿﻿module.exports = {
    downloadOperationPromise: null, // TODO concurrent operations support
    startAsync: function (success, fail, args) {
        try {
            var uri = new Windows.Foundation.Uri(args[0]),
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

            
            // TODO
            // After app termination, an app should enumerate all existing DownloadOperation instances at next start-up using
            // GetCurrentDownloadsAsync. When a Windows Store app using Background Transfer is terminated, incomplete downloads 
            // will persist in the background. If the app is restarted after termination and operations from the previous
            // session are not enumerated and re-attached to the current session, they will remain incomplete and continue to occupy resources
            // http://msdn.microsoft.com/library/windows/apps/br207126
            // TODO - resume incomplete download instead of triggering a new one
            // Windows.Networking.BackgroundTransfer.BackgroundDownloader.getCurrentDownloadsAsync().done(function (downloads) {
            //    for (var i = 0; i < downloads.size; i++) {
            //        console.log(downloads[i].toString());

            //         // to find existing download
            //        downloads[i].requestedUri.absoluteUri == uri.absoluteUri
                    
            //    }
            //}, function (error) {
            //    console.log(error.message);
            //});

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