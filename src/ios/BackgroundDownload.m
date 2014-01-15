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

@implementation BackgroundDownload
//https://developer.apple.com/library/IOs/samplecode/SimpleBackgroundTransfer/Introduction/Intro.html
// https://www.captechconsulting.com/blog/nicholas-cipollina/ios-7-tutorial-series-nsurlsession
// http://www.shinobicontrols.com/blog/posts/2013/09/20/ios7-day-by-day-day-1-nsurlsession/
//http://stackoverflow.com/questions/13996621/downloading-multiple-files-in-batches-in-ios
- (void)startAsync:(CDVInvokedUrlCommand*)command
{
    self.downloadUri = [command.arguments objectAtIndex:0];
    self.targetFile = [command.arguments objectAtIndex:1];
    
    self.callbackId = command.callbackId;
    
    NSURLRequest *request = [NSURLRequest requestWithURL:[NSURL URLWithString:self.downloadUri]];
    
    
    self.session = self.backgroundSession;
    self.downloadTask = [self.session downloadTaskWithRequest:request];
    
    [self.downloadTask resume];
    
}

- (NSURLSession *)backgroundSession
{
    static NSURLSession *backgroundSession = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSURLSessionConfiguration *config = [NSURLSessionConfiguration backgroundSessionConfiguration:@"com.cordova.plugin.BackgroundDownload.BackgroundSession"];
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
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}



- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didWriteData:(int64_t)bytesWritten totalBytesWritten:(int64_t)totalBytesWritten totalBytesExpectedToWrite:(int64_t)totalBytesExpectedToWrite {
    if (downloadTask == self.downloadTask){
        int64_t progress = 100 * totalBytesWritten / totalBytesExpectedToWrite;
        
        NSMutableDictionary* progressObj = [NSMutableDictionary dictionaryWithCapacity:1];
        [progressObj setObject:[NSNumber numberWithInteger:progress] forKey:@"progress"];
        CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:progressObj];
        result.keepCallback = [NSNumber numberWithInteger: TRUE];
        [self.commandDelegate sendPluginResult:result callbackId:self.callbackId];
    }
}



- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(NSURL *)location {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    
    NSError *error;
    
    [fileManager removeItemAtPath:self.targetFile error:NULL];
    BOOL success = [fileManager copyItemAtPath:[location absoluteString] toPath:self.targetFile error:&error];
    
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.callbackId];
}





@end