//
//  MusicControlsInfo.h
//
//  Created by Juan Gonzalez on 12/16/16.
//
//

#ifndef MusicControlsInfo_h
#define MusicControlsInfo_h

#import <Foundation/Foundation.h>

@interface MusicControlsInfo : NSObject {}

@property (nonatomic, copy) NSString * artist;
@property (nonatomic, copy) NSString * track;
@property (nonatomic, copy) NSString * album;
@property (nonatomic, copy) NSString * ticker;
@property (nonatomic, copy) NSString * cover;
@property (nonatomic, assign) int duration;
@property (nonatomic, assign) int elapsed;
@property (nonatomic, assign) BOOL isPlaying;
@property (nonatomic, assign) BOOL hasPrev;
@property (nonatomic, assign) BOOL hasNext;
@property (nonatomic, assign) BOOL hasSkipForward;
@property (nonatomic, assign) BOOL hasSkipBackward;
@property (nonatomic, assign) BOOL hasScrubbing;
@property (nonatomic, assign) int skipForwardInterval;
@property (nonatomic, assign) int skipBackwardInterval;
@property (nonatomic, copy) NSString * dismissable;

- (id) initWithDictionary: (NSDictionary *) dictionary;

@end

#endif /* MusicControlsInfo_h */
