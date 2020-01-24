/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License. You may obtain a copy of the License at
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied. See the License for the
 specific language governing permissions and limitations
 under the License.
 */

#import "BackgroundDownload.h"

static NSString* const CONFIGURATION_REQUEST_HEADERS = @"requestHeaders";
static NSString* const CONFIGURATION_TARGET_FILE = @"targetFile";
static NSString* const CONFIGURATION_DOWNLOAD_URL = @"downloadURL";
static NSString* const CONFIGURATION_SESSION_ID = @"sessionId";
static NSString* const CONFIGURATION_DOWNLOAD_DELAY = @"downloadDelay";
static NSString* const RESPONSE_HEADERS = @"headers";
static NSString* const RESPONSE_STATUS_CODE = @"statusCode";
static NSString* const RESPONSE_DOWNLOADED_FILE = @"downloadedFile";
static NSString* const LOG_TAG = @"[ BackgroundDownload ] : ";

@implementation BackgroundDownload {
    bool ignoreNextError;
    NSMutableDictionary *activeDownloads;
}

@synthesize session;

- (Download *) downloadItemWithKey:(NSString *) downloadItemKey {
    return self->activeDownloads ? [self->activeDownloads valueForKey:downloadItemKey] : nil;
}


- (Download *) downloadItemWithTask:(NSURLSessionTask *) task {
    NSURLRequest* originalRequest = [task originalRequest];
    NSString* downloadURL = [[originalRequest URL] absoluteString];
    NSDictionary* requestHeaders = [originalRequest allHTTPHeaderFields];

    NSString* downloadItemKey = [self downloadItemKey:downloadURL requestHeaders:requestHeaders];

    if (!self->activeDownloads) {
        return nil;
    }

    @synchronized (self) {
        Download* selectedDownload = [self downloadItemWithKey:downloadItemKey];
        return selectedDownload;
    }

    return nil;
}

- (NSString *) downloadItemKey:(NSString *) downloadURL requestHeaders:(NSDictionary *) requestHeaders {
    return [@"" stringByAppendingFormat:@"%@_%@",downloadURL, [requestHeaders.allValues componentsJoinedByString:@"_"]];
}

- (void)startAsync:(CDVInvokedUrlCommand*)command
{
    NSDictionary *downloadConfiguration = [command.arguments objectAtIndex:0];

    if (!self->activeDownloads) {
        self->activeDownloads = [[NSMutableDictionary alloc] init];
    }

    self.session = [self backgroundSession:[downloadConfiguration valueForKey:CONFIGURATION_SESSION_ID]];

    [session getTasksWithCompletionHandler:^(NSArray *dataTasks, NSArray *uploadTasks, NSArray *downloadTasks) {
        NSString* downloadUrl = [downloadConfiguration valueForKey:CONFIGURATION_DOWNLOAD_URL];
        NSMutableDictionary *requestHeaders = [downloadConfiguration valueForKey:CONFIGURATION_REQUEST_HEADERS];
        NSString* downloadItemKey = [self downloadItemKey:downloadUrl requestHeaders:requestHeaders];

        NSDate* earliestBeginDate = nil;
        if([downloadConfiguration valueForKey:CONFIGURATION_DOWNLOAD_DELAY] != NULL) {
            double downloadDelay =[[downloadConfiguration valueForKey:CONFIGURATION_DOWNLOAD_DELAY] doubleValue];
            earliestBeginDate = [[[NSDate alloc] init] dateByAddingTimeInterval:downloadDelay];
        }

        Download* downloadItem = [self->activeDownloads valueForKey:downloadItemKey];

        if (!downloadItem) {

            NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:downloadUrl]];

            if(requestHeaders != NULL) {
                for (NSString *key in requestHeaders) {
                    [request addValue:requestHeaders[key] forHTTPHeaderField:key];
                }
            }

            downloadItem = [[Download alloc] initWithConfiguration:downloadConfiguration callbackId:command.callbackId task:nil];

            [self attachToExistingDownload:downloadTasks downloadItem:downloadItem];


            if (downloadItem.task == nil) {
                downloadItem.task = [self.session downloadTaskWithRequest:request];

                if(earliestBeginDate != nil) {
                    [downloadItem.task setEarliestBeginDate:earliestBeginDate];
                }
            }

            @synchronized (self->activeDownloads) {
                [self->activeDownloads setObject:downloadItem forKey:downloadItemKey];
            }
        }

        [downloadItem.task resume];

        self->ignoreNextError = NO;

        if (downloadTasks.count > 0) {
            for(NSInteger i = 0; i < downloadTasks.count; i++) {
                if (![downloadTasks[i] isEqual:downloadItem.task]) {
                    [downloadTasks[i] resume];
                }
            }
        }
    }];
}

- (NSURLSession *)backgroundSession:(NSString *) sessionId
{
    static NSURLSession *backgroundSession = nil;
    static dispatch_once_t onceToken;

    NSString* configurationIdPrefix = @"com.cordova.plugin.BackgroundDownload.BackgroundSession";
    NSMutableString *configurationId = [[NSMutableString alloc] init];

    [configurationId appendString:configurationIdPrefix];
    if(sessionId != nil){
        [configurationId appendFormat:@".%@",sessionId];
    }

    dispatch_once(&onceToken, ^{
        NSURLSessionConfiguration *config = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:configurationId];
        backgroundSession = [NSURLSession sessionWithConfiguration:config delegate:self delegateQueue:nil];
    });
    return backgroundSession;
}

- (void)stop:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    NSDictionary* downloadConfiguration = [command.arguments objectAtIndex:0];

    if (downloadConfiguration != nil && [downloadConfiguration valueForKey:CONFIGURATION_DOWNLOAD_URL] != nil) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        NSString* downloadItemKey = [self downloadItemKey:[downloadConfiguration valueForKey:CONFIGURATION_DOWNLOAD_URL] requestHeaders:[downloadConfiguration valueForKey:CONFIGURATION_REQUEST_HEADERS]];

        [self cleanUpWithKey:downloadItemKey];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Arg was null"];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void) attachToExistingDownload:(NSArray *)downloadTasks downloadItem:(Download *) downloadItem
{
    for(NSInteger i = 0; i < downloadTasks.count; i++) {
        NSURLRequest* originalRequest = ((NSURLSessionDownloadTask *)downloadTasks[i]).originalRequest;

        NSString * existingDownloadItemKey = [self downloadItemKey:[[originalRequest URL] absoluteString] requestHeaders:[originalRequest allHTTPHeaderFields]];

        NSString* downloadItemKey = [self downloadItemKey:[downloadItem.configuration valueForKey:CONFIGURATION_DOWNLOAD_URL] requestHeaders:[downloadItem.configuration valueForKey:CONFIGURATION_REQUEST_HEADERS]];

        if ([existingDownloadItemKey isEqual:downloadItemKey]) {
            downloadItem.task = downloadTasks[i];
            break;
        }
    }
}

- (void) cleanUp:(Download *) downloadItem
{
    if (!downloadItem) {
        return;
    }

    [downloadItem.task cancel];
    @synchronized (self->activeDownloads) {
        NSString* downloadItemKey = [self downloadItemKey:[downloadItem.configuration valueForKey:CONFIGURATION_DOWNLOAD_URL] requestHeaders:[downloadItem.configuration valueForKey:CONFIGURATION_REQUEST_HEADERS]];

        [self->activeDownloads removeObjectForKey:downloadItemKey];
    }
}

-(void) cleanUpWithKey:(NSString*) downloadItemKey
{
    Download *curDownload = [self downloadItemWithKey:downloadItemKey];
    [self cleanUp:curDownload];
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didWriteData:(int64_t)bytesWritten totalBytesWritten:(int64_t)totalBytesWritten totalBytesExpectedToWrite:(int64_t)totalBytesExpectedToWrite
{
    Download * curDownload = [self downloadItemWithTask:downloadTask];
    if (!curDownload)
        return;

    NSMutableDictionary* progressObj = [NSMutableDictionary dictionaryWithCapacity:1];
    [progressObj setObject:[NSNumber numberWithInteger:totalBytesWritten] forKey:@"bytesReceived"];
    [progressObj setObject:[NSNumber numberWithInteger:totalBytesExpectedToWrite] forKey:@"totalBytesToReceive"];
    NSMutableDictionary* resObj = [NSMutableDictionary dictionaryWithCapacity:1];
    [resObj setObject:progressObj forKey:@"progress"];
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resObj];
    result.keepCallback = [NSNumber numberWithInteger: TRUE];
    [self.commandDelegate sendPluginResult:result callbackId:curDownload.callbackId];
}

-(void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error
{
    Download * curDownload = [self downloadItemWithTask:task];
    if (!curDownload)
        return;

    CDVPluginResult* pluginResult;
    
    NSString* downloadItemKey = [self downloadItemKey:[curDownload.configuration valueForKey:CONFIGURATION_DOWNLOAD_URL] requestHeaders:[curDownload.configuration valueForKey:CONFIGURATION_REQUEST_HEADERS]];

    NSInteger statusCode = [(NSHTTPURLResponse *)[task response] statusCode];
    if (statusCode >= 400) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[NSHTTPURLResponse localizedStringForStatusCode:statusCode]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:curDownload.callbackId];
        @synchronized (self) {
            [self->activeDownloads removeObjectForKey:downloadItemKey];
        }
        return;
    }
    if (error != nil) {
        if (ignoreNextError) {
            ignoreNextError = NO;
            return;
        }
        if (error.code == -999) {
            NSData* resumeData = [[error userInfo] objectForKey:NSURLSessionDownloadTaskResumeData];
            // resumeData is available only if operation was terminated by the system (no connection or other reason)
            // this happens when application is closed when there is pending download, so we try to resume it
            if (resumeData != nil) {
                ignoreNextError = YES;
                [curDownload.task cancel];
                curDownload.task = [self.session downloadTaskWithResumeData:resumeData];
                [curDownload.task resume];
                return;
            }
        }
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[error localizedDescription]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:curDownload.callbackId];
    } if (curDownload.error != nil) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:curDownload.error];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:curDownload.callbackId];
    } else {
        NSLog(@"%@ Successfully downloaded the file", LOG_TAG);
        
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:curDownload.successPayload];
        
        [self.commandDelegate sendPluginResult:pluginResult callbackId:curDownload.callbackId];
    }

    @synchronized (self) {
        [self->activeDownloads removeObjectForKey:downloadItemKey];
    }
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(NSURL *)location {
    Download * curDownload = [self downloadItemWithTask:downloadTask];
    if (!curDownload){
        NSLog(@"%@ No current download available", LOG_TAG);
        return;
    }
        

    NSFileManager *fileManager = [NSFileManager defaultManager];

    NSURL *targetURL = [NSURL URLWithString:[curDownload.configuration valueForKey:CONFIGURATION_TARGET_FILE]];

    NSError * error;

    NSHTTPURLResponse* downloadTaskResponse = (NSHTTPURLResponse *)downloadTask.response;
    
    [curDownload.successPayload setValue:[NSNumber numberWithLong:downloadTaskResponse.statusCode] forKey:RESPONSE_STATUS_CODE];
    [curDownload.successPayload setValue:downloadTaskResponse.allHeaderFields forKey:RESPONSE_HEADERS];
    [curDownload.successPayload setValue:[curDownload.configuration valueForKey:CONFIGURATION_TARGET_FILE] forKey:RESPONSE_DOWNLOADED_FILE];

    // remove already existing file
    [fileManager removeItemAtPath:targetURL.path error: nil];

    bool result = [fileManager moveItemAtURL:location toURL:targetURL error:&error];
    if (result) {
        NSLog(@"%@ Successfully moved the downloaded file to %@", LOG_TAG, targetURL);
        return;
    }

    result = [fileManager copyItemAtURL:location toURL:targetURL error:&error];
    if (result) {
        NSLog(@"%@ Successfully copied the downloaded file to %@", LOG_TAG, targetURL);
        return;
    }

    NSString *errorCode = @"";
    if (error != nil) {
        NSLog(@"%@ Error in copying the downloaded file to persistent location: %@", LOG_TAG, error.localizedDescription);
        errorCode = [[NSString alloc] initWithFormat:@" - %@ \n %@ (%ld)", error.localizedFailureReason, error.localizedDescription, error.code];
    }

    curDownload.error = [@"Cannot copy from temporary path to actual path " stringByAppendingString:errorCode];
}
@end

@implementation Download

- (id) initWithConfiguration:(NSDictionary *)downloadConfiguration callbackId:(NSString *)callbackId task:(NSURLSessionDownloadTask *)task {
    if ( self = [super init] ) {
        self.configuration = downloadConfiguration;
        self.error = nil;
        self.callbackId = callbackId;
        self.task = task;
        self.successPayload = [[NSMutableDictionary alloc] init];
        return self;
    }
    return nil;
}

@end
