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

@implementation BackgroundDownload {
    bool ignoreNextError;
    NSMutableDictionary *activeDownloads;
}

@synthesize session;

- (Download *) downloadItemWithUri:(NSString *) uri {
    return activeDownloads ? [activeDownloads valueForKey:uri] : nil;
}


- (Download *) downloadItemWithTask:(NSURLSessionTask *) task {
    if (!activeDownloads) {
        return nil;
    }
    @synchronized (self) {
        for(NSInteger i = 0; i < activeDownloads.count; i++){
            Download* downloadItem = activeDownloads.allValues[i];
            if (downloadItem.task == task) {
                return downloadItem;
            }
        }
    }
    return nil;
}

- (void)startAsync:(CDVInvokedUrlCommand*)command
{
    if (!activeDownloads) {
        activeDownloads = [[NSMutableDictionary alloc] init];
    }

    session = [self backgroundSession];

    [session getTasksWithCompletionHandler:^(NSArray *dataTasks, NSArray *uploadTasks, NSArray *downloadTasks) {
        NSString *uri = [command.arguments objectAtIndex:0];
        Download *downloadItem = [activeDownloads valueForKey:uri];
        NSString *uriMatcher = nil;
        if (command.arguments.count > 2 &&
            ![[command.arguments objectAtIndex:2] isEqual:[NSNull null]]) {
            uriMatcher = [command.arguments objectAtIndex:2];
        }

        if (!downloadItem) {
            NSURLRequest *request = [NSURLRequest requestWithURL:[NSURL URLWithString:uri]];
            downloadItem = [[Download alloc] initWithPath:[command.arguments objectAtIndex:1]
                                                      uri:uri
                                               uriMatcher:uriMatcher
                                               callbackId:command.callbackId
                                                     task:nil];
            [self attachToExistingDownload:downloadTasks downloadItem:downloadItem];
            if (downloadItem.task == nil) {
                downloadItem.task = [session downloadTaskWithRequest:request];
            }

            @synchronized (activeDownloads) {
                [activeDownloads setObject:downloadItem forKey:downloadItem.uriString];
            }
        }

        [downloadItem.task resume];

        ignoreNextError = NO;

        if (downloadTasks.count > 0) {
            for(NSInteger i = 0; i < downloadTasks.count; i++) {
                if (![downloadTasks[i] isEqual:downloadItem.task]) {
                    [downloadTasks[i] resume];
                }
            }
        }
    }];
}

- (NSURLSession *)backgroundSession
{
    static NSURLSession *backgroundSession = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSURLSessionConfiguration *config = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:@"com.cordova.plugin.BackgroundDownload.BackgroundSession"];
        backgroundSession = [NSURLSession sessionWithConfiguration:config delegate:self delegateQueue:nil];
    });
    return backgroundSession;
}

- (void)stop:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    NSString* myarg = [command.arguments objectAtIndex:0];

    if (myarg != nil) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Arg was null"];
    }

    [self cleanUpWithUri:[command.arguments objectAtIndex:0]];

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void) attachToExistingDownload:(NSArray *)downloadTasks downloadItem:(Download *) downloadItem
{
    NSRegularExpression *regex = nil;
    if (downloadItem.uriMatcher != nil && ![downloadItem.uriMatcher isEqual:@""]) {
        regex = [NSRegularExpression regularExpressionWithPattern:downloadItem.uriMatcher
                                                          options:NSRegularExpressionCaseInsensitive
                                                            error:nil];
    }
    for(NSInteger i = 0; i < downloadTasks.count; i++) {
        NSString * existingUrl = ((NSURLSessionDownloadTask *)downloadTasks[i]).originalRequest.URL.absoluteString;
        bool urlMatches = false;
        if (regex != nil) {
            NSString *substringForExistingUrlMatch = nil;
            NSString *substringForNewUrlMatch = nil;
            NSRange rangeOfExistingUrlMatch = [regex rangeOfFirstMatchInString:existingUrl
                                                                       options:0
                                                                         range:NSMakeRange(0, [existingUrl length])];
            if (!NSEqualRanges(rangeOfExistingUrlMatch, NSMakeRange(NSNotFound, 0))) {
                substringForExistingUrlMatch = [existingUrl substringWithRange:rangeOfExistingUrlMatch];
            }
            NSRange rangeOfNewUrlMatch = [regex rangeOfFirstMatchInString:downloadItem.uriString
                                                                       options:0
                                                                         range:NSMakeRange(0, [downloadItem.uriString length])];
            if (!NSEqualRanges(rangeOfNewUrlMatch, NSMakeRange(NSNotFound, 0))) {
                substringForNewUrlMatch = [downloadItem.uriString substringWithRange:rangeOfNewUrlMatch];
            }
            
            urlMatches = substringForExistingUrlMatch != nil &&
                                substringForNewUrlMatch != nil &&
                                [substringForExistingUrlMatch isEqual:substringForNewUrlMatch];
        }

        if (urlMatches || [existingUrl isEqual:downloadItem.uriString]) {
            downloadItem.task = downloadTasks[i];
            return;
        }
    }
}

- (void) cleanUp:(Download *) downloadItem
{
    if (!downloadItem) {
        return;
    }

    [downloadItem.task cancel];
    @synchronized (activeDownloads) {
        [activeDownloads removeObjectForKey:downloadItem.uriString];
    }
}

-(void) cleanUpWithUri:(NSString*) uri
{
    Download *curDownload = [self downloadItemWithUri: uri];
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
    NSInteger statusCode = [(NSHTTPURLResponse *)[task response] statusCode];
    if (statusCode >= 400) {
        CDVPluginResult* errorResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[NSHTTPURLResponse localizedStringForStatusCode:statusCode]];
        [self.commandDelegate sendPluginResult:errorResult callbackId:curDownload.callbackId];
        @synchronized (self) {
            [activeDownloads removeObjectForKey:curDownload.uriString];
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
        CDVPluginResult* errorResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[error localizedDescription]];
        [self.commandDelegate sendPluginResult:errorResult callbackId:curDownload.callbackId];
    } if (curDownload.error != nil) {
        CDVPluginResult* errorResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:curDownload.error];
        [self.commandDelegate sendPluginResult:errorResult callbackId:curDownload.callbackId];
    } else {
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:curDownload.callbackId];
    }

    @synchronized (self) {
        [activeDownloads removeObjectForKey:curDownload.uriString];
    }
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(NSURL *)location {
    Download * curDownload = [self downloadItemWithTask:downloadTask];
    if (!curDownload)
        return;

    NSFileManager *fileManager = [NSFileManager defaultManager];

    NSURL *targetURL = [NSURL URLWithString:curDownload.filePath];

    [fileManager removeItemAtPath:targetURL.path error: nil];

    NSError * error;
    bool result = [fileManager moveItemAtURL:location toURL:targetURL error:&error];
    if (result) {
        return;
    }

    result = [fileManager copyItemAtURL:location toURL:targetURL error:&error];
    if (result) {
        return;
    }

    NSString *errorCode = @"";
    if (error != nil) {
        errorCode = [[NSString alloc] initWithFormat:@" - (%d)", error.code];
    }

    curDownload.error = [@"Cannot copy from temporary path to actual path " stringByAppendingString:errorCode];
}
@end

@implementation Download

- (id) initWithPath:(NSString *)filePath uri:(NSString *)uri uriMatcher:(NSString *)uriMatcher callbackId:(NSString *)callbackId task:(NSURLSessionDownloadTask *)task {
    if ( self = [super init] ) {
        self.error = nil;
        self.filePath = filePath;
        self.uriString = uri;
        self.uriMatcher = uriMatcher;
        self.callbackId = callbackId;
        self.task = task;
        return self;
    }
    return nil;
}

@end
