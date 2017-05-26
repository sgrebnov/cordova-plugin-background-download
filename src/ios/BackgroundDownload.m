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

NSString *const FLTDownloadQueue = @"FLTDownloadQueue";

@implementation BackgroundDownload {
    bool ignoreNextError;
}

@synthesize session;
@synthesize currentDownloadTask;

- (void)startAsync:(CDVInvokedUrlCommand*)command
{
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSURLSessionConfiguration *defaultConfigObject = [NSURLSessionConfiguration defaultSessionConfiguration];
        session = [NSURLSession sessionWithConfiguration: defaultConfigObject delegate: self delegateQueue: nil];
        
        [[NSUserDefaults standardUserDefaults] removeObjectForKey:FLTDownloadQueue];
        [[NSUserDefaults standardUserDefaults] synchronize];
    });
    
    NSString* downloadUri = [command.arguments objectAtIndex:0];
    self.targetFile = [command.arguments objectAtIndex:1];
    self.callbackId = command.callbackId;
    
    NSURLRequest* request = [NSURLRequest requestWithURL:[NSURL URLWithString:downloadUri]];
    
    NSURLSessionDownloadTask* dataTask = [session downloadTaskWithRequest:request];
    
    NSArray* array = [[NSUserDefaults standardUserDefaults] valueForKey:FLTDownloadQueue];
    
    if (array.count > 0)
    {
        NSMutableArray* mutArray = [NSMutableArray arrayWithArray:array];
        [mutArray addObject:@{@"taskIdentifier" : [@(dataTask.taskIdentifier) stringValue],
                              @"url" : [command.arguments objectAtIndex:0],
                              @"savedFilePath" : [command.arguments objectAtIndex:1],
                              @"callbackId" : command.callbackId,
                              @"message" : @""}];
        array = [NSArray arrayWithArray:mutArray];
    }
    else
    {
        array = @[@{@"taskIdentifier" : [@(dataTask.taskIdentifier) stringValue],
                    @"url" : [command.arguments objectAtIndex:0],
                    @"savedFilePath" : [command.arguments objectAtIndex:1],
                    @"callbackId" : command.callbackId,
                    @"message" : @""}];
    }
    
    [[NSUserDefaults standardUserDefaults] setObject:array forKey:FLTDownloadQueue];
    [[NSUserDefaults standardUserDefaults] synchronize];
    
    [dataTask resume];
}

- (NSURLSession *)backgroundSession
{
    NSURLSessionConfiguration *defaultConfigObject = [NSURLSessionConfiguration defaultSessionConfiguration];
    NSURLSession *defaultSession = [NSURLSession sessionWithConfiguration: defaultConfigObject delegate: self delegateQueue: nil];
    return defaultSession;
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
    
    [currentDownloadTask cancel];
    
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didWriteData:(int64_t)bytesWritten totalBytesWritten:(int64_t)totalBytesWritten totalBytesExpectedToWrite:(int64_t)totalBytesExpectedToWrite
{
    NSMutableDictionary* progressObj = [NSMutableDictionary dictionaryWithCapacity:1];
    [progressObj setObject:[NSNumber numberWithInteger:totalBytesWritten] forKey:@"bytesReceived"];
    [progressObj setObject:[NSNumber numberWithInteger:totalBytesExpectedToWrite] forKey:@"totalBytesToReceive"];
    NSMutableDictionary* resObj = [NSMutableDictionary dictionaryWithCapacity:1];
    [resObj setObject:progressObj forKey:@"progress"];
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resObj];
    result.keepCallback = [NSNumber numberWithInteger: TRUE];
    [self.commandDelegate sendPluginResult:result callbackId:self.callbackId];
}

-(void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error
{
    if (ignoreNextError) {
        ignoreNextError = NO;
        return;
    }
    
    NSArray* array = [[NSUserDefaults standardUserDefaults] valueForKey:FLTDownloadQueue];
    NSDictionary* dictTask = nil;
    
    for (NSDictionary* dict in array)
    {
        if ([dict[@"taskIdentifier"] isEqualToString:[@(task.taskIdentifier) stringValue]])
        {
            dictTask = dict;
            break;
        }
    }
    
    if (error != nil) {
        if ((error.code == -999)) {
            NSData* resumeData = [[error userInfo] objectForKey:NSURLSessionDownloadTaskResumeData];
            // resumeData is available only if operation was terminated by the system (no connection or other reason)
            // this happens when application is closed when there is pending download, so we try to resume it
            if (resumeData != nil) {
                ignoreNextError = YES;
                [currentDownloadTask cancel];
                currentDownloadTask = [self.session downloadTaskWithResumeData:resumeData];
                [currentDownloadTask resume];
                return;
            }
        }
        
        NSDictionary* response = @{@"fileData" : @{@"savedFilePath" : dictTask[@"savedFilePath"]},
                                   @"message" : [error localizedDescription],
                                   @"reason" : [NSNumber numberWithInteger:error.code],
                                   @"url" : dictTask[@"url"]};
        
        CDVPluginResult* errorResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:response];
        [errorResult setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:errorResult callbackId:dictTask[@"callbackId"]];
        return;
    }
    
    CDVCommandStatus commandStatus = CDVCommandStatus_OK;
    NSHTTPURLResponse *httpResponse = (NSHTTPURLResponse *) task.response;
    
    if  ([httpResponse statusCode] != 200){
        commandStatus = CDVCommandStatus_ERROR;
    }
    
    NSDictionary* response = @{@"fileData" : @{@"savedFilePath" : dictTask[@"savedFilePath"],
                                               @"message" : dictTask[@"message"],
                                               @"reason" : [NSNumber numberWithInteger:[httpResponse statusCode]],
                                               @"url" : dictTask[@"url"]}};
    
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:commandStatus messageAsDictionary:response];
    [pluginResult setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:dictTask[@"callbackId"]];
    
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(NSURL *)location
{
    NSMutableArray* array = [NSMutableArray arrayWithArray:[[NSUserDefaults standardUserDefaults] valueForKey:FLTDownloadQueue]];
    
    for (NSDictionary* dict in [array copy])
    {
        if ([dict[@"taskIdentifier"] isEqualToString:[@(downloadTask.taskIdentifier) stringValue]])
        {
            NSFileManager *fileManager = [NSFileManager defaultManager];
            NSURL *targetURL = [NSURL URLWithString:dict[@"savedFilePath"]];
            NSHTTPURLResponse *httpResponse = (NSHTTPURLResponse *) downloadTask.response;
            
            [fileManager removeItemAtPath:targetURL.path error: nil];
            
            if ([httpResponse statusCode] == 200) {
                [fileManager createFileAtPath:targetURL.path contents:[fileManager contentsAtPath:[location path]] attributes:nil];
            } else {
                NSData *fileData = [NSData dataWithContentsOfFile:[location path]];
                NSError *error = nil;
                NSDictionary *fileDict = [NSJSONSerialization JSONObjectWithData:fileData options:kNilOptions error:&error];
                NSMutableDictionary *muteDict = [dict mutableCopy];
                
                if (fileDict[@"message"])
                {
                    muteDict[@"message"] = fileDict[@"message"];
                    [array replaceObjectAtIndex:[array indexOfObject:dict] withObject:muteDict];
                    [[NSUserDefaults standardUserDefaults] setObject:array forKey:FLTDownloadQueue];
                    [[NSUserDefaults standardUserDefaults] synchronize];
                }
            }
            break;
        }
    }
}

@end
