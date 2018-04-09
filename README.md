Background Download plugin for Apache Cordova
==================================
API provides an advanced file download functionality that persists beyond app termination, runs in the background and continues even when the user closed/suspended the application. The plugin includes progress updates and primarily designed for long-term transfer operations for resources like video, music, and large images.

**Sample usage**

        var fileName = "PointerEventsCordovaPlugin.wmv",
            uriString = "http://media.ch9.ms/ch9/8c03/f4fe2512-59e5-4a07-bded-124b06ac8c03/PointerEventsCordovaPlugin.wmv";
        
        // open target file for download
        window.requestFileSystem(LocalFileSystem.PERSISTENT, 0, function(fileSystem) {
            fileSystem.root.getFile(fileName, { create: true }, function (targetFile) {
                
                var onSuccess, onError, onProgress; // plugin callbacks to track operation execution status and progress
        
                var downloader = new BackgroundTransfer.BackgroundDownloader();
                // Create a new download operation.
                var download = downloader.createDownload(uriString, targetFile);
                // Start the download and persist the promise to be able to cancel the download.
                app.downloadPromise = download.startAsync().then(onSuccess, onError, onProgress);
            });
        });



**Internal vs External (SD card) storage on Android**

- ***External Storage***
  
  - add the following section to config.xml
  
    `<preference name="AndroidPersistentFileLocation" value="Compatibility" />`
        
    ```
    window.requestFileSystem(LocalFileSystem.PERSISTENT, 0, function(fileSystem) {
            fileSystem.root.getFile(fileName, { create: true }, function (targetFile) {
                ...
            })
        }, function(error) {...})
    ```
    
  - or resolve `sdcard` directory in runtime
    ```
    window.resolveLocalFileSystemURL('cdvfile://localhost/sdcard/', function(dirEntry) {
            dirEntry.getFile(fileName, { create: true }, function (targetFile) {
                ...
            })
        }, function(error) {...})
    ```

- ***Internal Storage***
  - remove `AndroidPersistentFileLocation` preference from config.xml or set in to `Internal`
  
    `<preference name="AndroidPersistentFileLocation" value="Internal" />`
    
    ```
    window.requestFileSystem(LocalFileSystem.PERSISTENT, 0, function(fileSystem) {
            fileSystem.root.getFile(fileName, { create: true }, function (targetFile) {
                ...
            })
        }, function(error) {...})
    ```
  - or resolve `files` directory in runtime
    ```
    window.resolveLocalFileSystemURL('cdvfile://localhost/files/', function(dirEntry) {
            dirEntry.getFile(fileName, { create: true }, function (targetFile) {
                ...
            })
        }, function(error) {...})
    ```

Read File Plugin quirks for more details:
- https://cordova.apache.org/docs/en/latest/reference/cordova-plugin-file/#android-quirks.
- https://cordova.apache.org/docs/en/latest/reference/cordova-plugin-file/#configuring-the-plugin-optiona

**Supported platforms**
 
 * Windows8
 * Windows Phone8
 * iOS 7.0 or later
 * Android
 
**Quirks**
 * If a download operation was completed when the application was in the background, onSuccess callback is called when the application become active.
 * If a download operation was completed when the application was closed, onSuccess callback is called right after the first startAsync() is called for the same uri, as if the file has been downloaded immediately.
 * A new download operation for the same uri resumes a pending download instead of triggering a new one. If no pending downloads found for the uri specified, a new download is started, the target file will be automatically overwritten once donwload is completed.
 * On Android temporary downloading file is created on external storage (limitation of DownloadManager), so if there is no external storage the downloading will fail.
