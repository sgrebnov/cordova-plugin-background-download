module.exports = {
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

            var downloadLocation = null;
            Windows.Storage.StorageFile.getFileFromPathAsync(resultFilePath).then(
                function (file) {
                    downloadLocation = file;
                }, errorHandler).then(function () {
                    return Windows.Networking.BackgroundTransfer.BackgroundDownloader.getCurrentDownloadsAsync();
                }, errorHandler).then(function (downloads) {
                    var downloadOperation = null;

                    // After app termination, an app should enumerate all existing DownloadOperation instances at next start-up using
                    // GetCurrentDownloadsAsync. When a Windows Store app using Background Transfer is terminated, incomplete downloads 
                    // will persist in the background. If the app is restarted after termination and operations from the previous
                    // session are not enumerated and re-attached to the current session, they will remain incomplete and continue to occupy resources
                    // http://msdn.microsoft.com/library/windows/apps/br207126
                    for (var i = 0; i < downloads.size; i++) {
                        if (downloads[i].requestedUri.absoluteUri == uri.absoluteUri) {
                            downloadOperationPromise = downloads[i].attachAsync();
                            return downloadOperationPromise;
                        }

                    }

                    // new download
                    downloadOperation = Windows.Networking.BackgroundTransfer.BackgroundDownloader().createDownload(uri, downloadLocation);
                    downloadOperationPromise = downloadOperation.startAsync();

                    return downloadOperationPromise;

                }, errorHandler).then(completeHandler, errorHandler, progressHandler);
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