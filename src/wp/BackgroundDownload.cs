using System;
using System.IO;
using System.IO.IsolatedStorage;
using System.Net;
using System.Threading.Tasks;

namespace WPCordovaClassLib.Cordova.Commands
{       
    /// <summary>
    /// TODO comments
    /// TODO concurrent operations support
    /// Throws cancelled if application goes to background.
    /// Possible workaround
    /// Try BackgroundTransfer Api
    /// http://msdn.microsoft.com/en-us/library/windowsphone/develop/hh202955%28v=vs.105%29.aspx#BKMK_TheBackgroundTransferAPIs
    /// Or custom logic: read data from server by chunks via custom implementation
    /// </summary>
    class BackgroundDownload : BaseCommand
    {
        private string _uri;
        private string _targetFilePath;
        private WebClient _webClient;
        private string _callbackId;

        public void startAsync(string options)
        {
            try
            {
                var optStings = JSON.JsonHelper.Deserialize<string[]>(options);
                _uri = optStings[0]+"?key=" + Guid.NewGuid();
                _targetFilePath = optStings[1];
                _callbackId = optStings[2];

                _webClient = new WebClient();
                _webClient.OpenReadCompleted += DowloadCompleted;
                _webClient.DownloadProgressChanged += ProgressChanged;

                _webClient.OpenReadAsync(new Uri(_uri));
                
            }
            catch (Exception ex)
            {               
                DispatchCommandResult(new PluginResult(PluginResult.Status.ERROR, ex.Message));
            }
        }

        public  void stop(string options)
        {
            try
            {
                if (_webClient != null)
                {
                    _webClient.CancelAsync();
                }

                DispatchCommandResult(new PluginResult(PluginResult.Status.OK));
            }
            catch (Exception ex)
            {
                DispatchCommandResult(new PluginResult(PluginResult.Status.ERROR, ex.Message));
            }
        }

        private void ProgressChanged(object sender, DownloadProgressChangedEventArgs e)
        {
            
            var progressUpdate = new PluginResult(PluginResult.Status.OK);

            progressUpdate.KeepCallback = true;
            progressUpdate.Message = String.Format("{{\"progress\":{0}}}", e.ProgressPercentage);

            DispatchCommandResult(progressUpdate, _callbackId);
        }

        private async void DowloadCompleted(object sender, OpenReadCompletedEventArgs e)
        {
            try
            {
                if (e.Cancelled)
                {
                    DispatchCommandResult(new PluginResult(PluginResult.Status.ERROR, "Cancelled"), _callbackId);
                    return;
                }

                if (e.Error != null)
                {
                    DispatchCommandResult(new PluginResult(PluginResult.Status.ERROR, e.Error.Message), _callbackId);
                    return;
                }

                await SaveToLocalFolderAsync(e.Result, _targetFilePath);

                DispatchCommandResult(new PluginResult(PluginResult.Status.OK), _callbackId);

            }
            catch (Exception ex)
            {
                DispatchCommandResult(new PluginResult(PluginResult.Status.ERROR, ex.Message));
            }
            finally
            {
                CleanUp();
            }
        }

        private async Task SaveToLocalFolderAsync(Stream file, string fileName)
        {
            using (IsolatedStorageFile isoFile = IsolatedStorageFile.GetUserStoreForApplication())
            {
                using (Stream outputStream = isoFile.CreateFile(fileName))
                {
                    await file.CopyToAsync(outputStream);
                }
            }
        }

        private void CleanUp()
        {
            if (_webClient == null) return;
            
            _webClient.OpenReadCompleted -= DowloadCompleted;
            _webClient.DownloadProgressChanged -= ProgressChanged;
            _webClient = null;
        }

    }
}