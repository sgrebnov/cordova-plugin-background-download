using System;
using System.IO.IsolatedStorage;
using System.Linq;
using Microsoft.Phone.BackgroundTransfer;

namespace WPCordovaClassLib.Cordova.Commands
{       
    /// <summary>
    /// TODO comments
    /// TODO concurrent operations support
    /// </summary>
    class BackgroundDownload : BaseCommand
    {
        private string _uriString;
        private string _filePath;
        private string _callbackId;
        private BackgroundTransferRequest _transfer;

        public void startAsync(string options)
        {
            try
            {
                var optStings = JSON.JsonHelper.Deserialize<string[]>(options);
                _uriString = optStings[0];
                _filePath = optStings[1];
                _callbackId = optStings[2];

                var requestUri = new Uri(_uriString);

                _transfer = FindTransfer(_filePath);

                if (_transfer == null)
                {
                    // "shared\transfers" is the only working location for BackgroundTransferService download
                    // we use temporary file name to download content and then move downloaded file to the requested location
                    var downloadLocation = new Uri(@"\shared\transfers\" + Guid.NewGuid(), UriKind.Relative);

                    _transfer = new BackgroundTransferRequest(requestUri, downloadLocation);

                    // Tag is used to make sure we run single background transfer for this file
                    _transfer.Tag = _filePath;

                    BackgroundTransferService.Add(_transfer);
                }

                if (_transfer.TransferStatus == TransferStatus.Completed)
                {
                    // file was already downloaded while we were in background and we didn't report this
                    MoveFile();
                    BackgroundTransferService.Remove(_transfer);
                    DispatchCommandResult(new PluginResult(PluginResult.Status.OK));
                }
                else
                {
                    _transfer.TransferProgressChanged += ProgressChanged;
                    _transfer.TransferStatusChanged += TransferStatusChanged;
                }
                
            }
            catch (Exception ex)
            {               
                DispatchCommandResult(new PluginResult(PluginResult.Status.ERROR, ex.Message));
            }
        }


        public void stop(string options)
        {
            try
            {

                if (_transfer != null)
                {
                    var request = BackgroundTransferService.Find(_transfer.RequestId);
                    if (request != null)
                    {
                        // stops transfer and triggers TransferStatusChanged event
                        BackgroundTransferService.Remove(request);
                    }
                }

                DispatchCommandResult(new PluginResult(PluginResult.Status.OK));
            }
            catch (Exception ex)
            {
                DispatchCommandResult(new PluginResult(PluginResult.Status.ERROR, ex.Message));
            }
        }

        private void TransferStatusChanged(object sender, BackgroundTransferEventArgs backgroundTransferEventArgs)
        {
            var transfer = backgroundTransferEventArgs.Request;

            if (transfer.TransferStatus == TransferStatus.Completed)
            {
                // If the status code of a completed _transfer is 200 or 206, the _transfer was successful
                if (transfer.StatusCode == 200 || transfer.StatusCode == 206)
                {
                    MoveFile();
                    DispatchCommandResult(new PluginResult(PluginResult.Status.OK), _callbackId);
                    
                }
                else
                {
                    var strErrorMessage = transfer.TransferError != null ? transfer.TransferError.Message : "Unspecified transfer error";
                    DispatchCommandResult(new PluginResult(PluginResult.Status.ERROR, strErrorMessage), _callbackId);

                }
                CleanUp();
            }
        }

        private static BackgroundTransferRequest FindTransfer(string tag)
        {
            return BackgroundTransferService.Requests.FirstOrDefault(r => r.Tag == tag);
        }

        void ProgressChanged(object sender, BackgroundTransferEventArgs e)
        {
            var progressUpdate = new PluginResult(PluginResult.Status.OK);

            progressUpdate.KeepCallback = true;
            progressUpdate.Message = String.Format("{{\"progress\":{0}}}", 100 * e.Request.BytesReceived / e.Request.TotalBytesToReceive);

            DispatchCommandResult(progressUpdate, _callbackId);
        }

        private void MoveFile()
        {
            // The downloaded content is moved into the right place
            using (var isoStore = IsolatedStorageFile.GetUserStoreForApplication())
            {
                string filename = _filePath;
                if (isoStore.FileExists(filename))
                {
                    isoStore.DeleteFile(filename);
                }
                isoStore.MoveFile(_transfer.DownloadLocation.OriginalString, filename);
            }
        }

        private void CleanUp()
        {
            if (_transfer != null)
            {
                _transfer.TransferProgressChanged -= ProgressChanged;
                _transfer.TransferStatusChanged -= TransferStatusChanged;

                if (BackgroundTransferService.Find(_transfer.RequestId) != null)
                {
                    BackgroundTransferService.Remove(_transfer);
                }

                _transfer = null;
            }
        }
    }
}