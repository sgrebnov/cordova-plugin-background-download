cordova-plugin-background-download
==================================

Performs asynchronous resource download operation on a regular basis. Plugin is designed for background work so operates even when the app is inactive; when the user launches or re-opens the app, it can start up with the most current data possible.

**Sample usage**

        var backgroundDownload = BackgroundDownloader.createDownload("uri", "fileName", 30);

        backgroundDownload.startAsync(function () {
            console.log('successfully started');
        },
        function(err) {
            console.log('error: ' + err);
        });
        

        backgroundDownload.stop(function () {
            console.log('successfully stopped');
        },
        function (err) {
            console.log('error: ' + err);
        });


Links to corresponsing WinRT Api (TODO: remove)

http://msdn.microsoft.com/en-us/library/windows/apps/windows.networking.backgroundtransfer.backgrounddownloader.aspx
http://msdn.microsoft.com/en-us/library/windows/apps/windows.networking.backgroundtransfer.downloadoperation.aspx
