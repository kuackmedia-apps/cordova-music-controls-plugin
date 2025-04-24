//
//  MusicControls.h
//
//  Created by Juan Gonzalez on 12/16/16.
//  Updated for AirPlay compatibility in iOS 14+ [2025]
//

#import <Cordova/CDVPlugin.h>
#import <MediaPlayer/MediaPlayer.h>
#import <MediaPlayer/MPNowPlayingInfoCenter.h>
#import <MediaPlayer/MPMediaItem.h>
#import <AVFoundation/AVFoundation.h>

@interface MusicControls : CDVPlugin {
    NSString * latestEventCallbackId;
}

@property (nonatomic, copy) NSString *latestEventCallbackId;
@property (nonatomic, strong) AVAudioSession *avSession;

// Métodos básicos de Cordova Plugin
- (void)create:(CDVInvokedUrlCommand*)command;
- (void)updateIsPlaying:(CDVInvokedUrlCommand*)command;
- (void)updateElapsed:(CDVInvokedUrlCommand*)command;
- (void)destroy:(CDVInvokedUrlCommand*)command;
- (void)watch:(CDVInvokedUrlCommand*)command;

// Métodos para artworks y media info
- (MPMediaItemArtwork*)createCoverArtwork:(NSString*)coverUri;
- (void)setCoverArtworkAsync:(NSString*)coverUri completion:(void (^)(MPMediaItemArtwork *))completion;
- (void)forceNowPlayingInfoRefresh;

// Gestión de eventos
- (void)registerMusicControlsEventListener;
- (void)deregisterMusicControlsEventListener;

// Soporte AirPlay específico
- (void)registerAirPlayObservers;
- (void)handleAudioRouteChange:(NSNotification *)notification;
- (void)refreshNowPlayingInfoForAirPlay;
- (void)handleAVPlayerExternalPlaybackActive:(NSNotification *)notification;
- (void)handleAVPlayerItemDidPlayToEndTime:(NSNotification *)notification;
- (void)handleAVPlayerItemFailedToPlayToEndTime:(NSNotification *)notification;

// Manejadores de eventos de comandos remotos
- (MPRemoteCommandHandlerStatus)togglePlayPauseEvent:(MPRemoteCommandEvent *)event;
- (MPRemoteCommandHandlerStatus)nextTrackEvent:(MPRemoteCommandEvent *)event;
- (MPRemoteCommandHandlerStatus)prevTrackEvent:(MPRemoteCommandEvent *)event;
- (MPRemoteCommandHandlerStatus)pauseEvent:(MPRemoteCommandEvent *)event;
- (MPRemoteCommandHandlerStatus)playEvent:(MPRemoteCommandEvent *)event;
- (MPRemoteCommandHandlerStatus)skipForwardEvent:(MPSkipIntervalCommandEvent *)event;
- (MPRemoteCommandHandlerStatus)skipBackwardEvent:(MPSkipIntervalCommandEvent *)event;
- (MPRemoteCommandHandlerStatus)changedThumbSliderOnLockScreen:(MPChangePlaybackPositionCommandEvent *)event;
- (MPRemoteCommandHandlerStatus)remoteEvent:(MPRemoteCommandEvent *)event;

@end
