//
//  MusicControlsInfo.m
//
//  Created by Juan Gonzalez on 12/16/16.
//  Updated for AirPlay compatibility [2025]
//

#import "MusicControlsInfo.h"

@implementation MusicControlsInfo

- (id) initWithDictionary: (NSDictionary *) dictionary {

    if (self = [super init]) {
        self.artist = [dictionary objectForKey:@"artist"];
        self.track = [dictionary objectForKey:@"track"];
        self.album = [dictionary objectForKey:@"album"];
        self.ticker = [dictionary objectForKey:@"ticker"];
        self.cover = [dictionary objectForKey:@"cover"];
        NSNumber* duration = [dictionary objectForKey:@"duration"];
        self.duration = (duration != nil && [duration isKindOfClass:[NSNumber class]]) ? [duration intValue] : 0;
        NSNumber* elapsed = [dictionary objectForKey:@"elapsed"];
        self.elapsed = (elapsed != nil && [elapsed isKindOfClass:[NSNumber class]]) ? [elapsed intValue] : 0;
        NSNumber* isPlaying = [dictionary objectForKey:@"isPlaying"];
        self.isPlaying = (isPlaying != nil && [isPlaying isKindOfClass:[NSNumber class]]) ? [isPlaying boolValue] : NO;
        NSNumber* hasPrev = [dictionary objectForKey:@"hasPrev"];
        self.hasPrev = (hasPrev != nil && [hasPrev isKindOfClass:[NSNumber class]]) ? [hasPrev boolValue] : NO;
        NSNumber* hasNext = [dictionary objectForKey:@"hasNext"];
        self.hasNext = (hasNext != nil && [hasNext isKindOfClass:[NSNumber class]]) ? [hasNext boolValue] : NO;
        NSNumber* hasSkipForward = [dictionary objectForKey:@"hasSkipForward"];
        self.hasSkipForward = (hasSkipForward != nil && [hasSkipForward isKindOfClass:[NSNumber class]]) ? [hasSkipForward boolValue] : NO;
        NSNumber* hasSkipBackward = [dictionary objectForKey:@"hasSkipBackward"];
        self.hasSkipBackward = (hasSkipBackward != nil && [hasSkipBackward isKindOfClass:[NSNumber class]]) ? [hasSkipBackward boolValue] : NO;
        NSNumber* hasScrubbing = [dictionary objectForKey:@"hasScrubbing"];
        self.hasScrubbing = (hasScrubbing != nil && [hasScrubbing isKindOfClass:[NSNumber class]]) ? [hasScrubbing boolValue] : NO;
        NSNumber* skipForwardInterval = [dictionary objectForKey:@"skipForwardInterval"];
        self.skipForwardInterval = (skipForwardInterval != nil && [skipForwardInterval isKindOfClass:[NSNumber class]]) ? [skipForwardInterval intValue] : 0;
        NSNumber* skipBackwardInterval = [dictionary objectForKey:@"skipBackwardInterval"];
        self.skipBackwardInterval = (skipBackwardInterval != nil && [skipBackwardInterval isKindOfClass:[NSNumber class]]) ? [skipBackwardInterval intValue] : 0;
        self.dismissable = [dictionary objectForKey:@"dismissable"];
    }

    return self;
}

@end
