//
//  MusicControls.m
//
//
//  Created by Juan Gonzalez on 12/16/16.
//  Updated by Gaven Henry on 11/7/17 for iOS 11 compatibility & new features
//  Updated by Eugene Cross on 14/10/19 for iOS 13 compatibility
//  Updated for AirPlay compatibility in iOS 14+ [2025]
//

#import "MusicControls.h"
#import "MusicControlsInfo.h"

//save the passed in info globally so we can configure the enabled/disabled commands and skip intervals
MusicControlsInfo * musicControlsSettings;
AVPlayer * avPlayer;

@implementation MusicControls

- (void)create:(CDVInvokedUrlCommand *)command {
    NSLog(@"🚩 MusicControls.create iniciado.");


    NSDictionary *musicControlsInfoDict = [command.arguments objectAtIndex:0];
    MusicControlsInfo *musicControlsInfo = [[MusicControlsInfo alloc] initWithDictionary:musicControlsInfoDict];
    musicControlsSettings = musicControlsInfo;

    if (!NSClassFromString(@"MPNowPlayingInfoCenter")) {
        NSLog(@"⚠️ MPNowPlayingInfoCenter no está disponible.");
        return;
    }

    NSLog(@"ℹ️ Iniciando carga asincrónica del artwork con URL: %@", [musicControlsInfo cover]);

    [self.commandDelegate runInBackground:^{
        [self setCoverArtworkAsync:[musicControlsInfo cover] completion:^(MPMediaItemArtwork *artwork) {
            MPNowPlayingInfoCenter *nowPlayingInfoCenter = [MPNowPlayingInfoCenter defaultCenter];
            NSMutableDictionary *updatedNowPlayingInfo = [NSMutableDictionary dictionary];

            if (artwork != nil) {
                NSLog(@"✅ Artwork cargado correctamente.");
                updatedNowPlayingInfo[MPMediaItemPropertyArtwork] = artwork;
            } else {
                NSLog(@"❌ Artwork no pudo ser cargado (nil).");
            }

            NSLog(@"🎵 Artist: %@", [musicControlsInfo artist]);
            NSLog(@"🎵 Track: %@", [musicControlsInfo track]);
            NSLog(@"🎵 Album: %@", [musicControlsInfo album]);

            updatedNowPlayingInfo[MPMediaItemPropertyArtist] = [musicControlsInfo artist];
            updatedNowPlayingInfo[MPMediaItemPropertyTitle] = [musicControlsInfo track];
            updatedNowPlayingInfo[MPMediaItemPropertyAlbumTitle] = [musicControlsInfo album];
            updatedNowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = @(musicControlsInfo.duration);
            updatedNowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(musicControlsInfo.elapsed);
            updatedNowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = [NSNumber numberWithDouble:[musicControlsInfo isPlaying] ? 1.0 : 0.0];

            // Adición específica para iOS 14+ y AirPlay
            if (@available(iOS 14.0, *)) {
                // Estas propiedades ayudan a AirPlay a manejar mejor los metadatos
                updatedNowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = @(NO);
                updatedNowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = @(MPNowPlayingInfoMediaTypeAudio);
                updatedNowPlayingInfo[MPNowPlayingInfoPropertyPlaybackQueueIndex] = @(0);
                updatedNowPlayingInfo[MPNowPlayingInfoPropertyPlaybackQueueCount] = @(1);
                updatedNowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = @(1.0);

                // Esta propiedad es crucial para identificación externa
                NSString *uniqueIdentifier = [NSString stringWithFormat:@"%@-%@-%@",
                                             [musicControlsInfo artist],
                                             [musicControlsInfo track],
                                             [musicControlsInfo album]];

                // Usar un identificador único pero estable para AirPlay
                NSUInteger hashValue = [uniqueIdentifier hash];
                NSNumber *persistentID = [NSNumber numberWithUnsignedLongLong:(uint64_t)hashValue];
                updatedNowPlayingInfo[MPMediaItemPropertyPersistentID] = persistentID;
            }

            dispatch_async(dispatch_get_main_queue(), ^{
                nowPlayingInfoCenter.nowPlayingInfo = updatedNowPlayingInfo;
                NSLog(@"ℹ️ NowPlayingInfo actualizado con éxito: %@", updatedNowPlayingInfo);

                // Forzar una actualización para AirPlay
                [self forceNowPlayingInfoRefresh];
            });
        }];
    }];

    [self deregisterMusicControlsEventListener];
    [self registerMusicControlsEventListener];
}

// Método para forzar la actualización de NowPlayingInfo (especialmente para AirPlay)
- (void)forceNowPlayingInfoRefresh {
    if (@available(iOS 14.0, *)) {
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        if (!center.nowPlayingInfo) return;
        // Re-assign triggers system update
        center.nowPlayingInfo = [NSDictionary dictionaryWithDictionary:center.nowPlayingInfo];
    }
}

- (void) updateIsPlaying: (CDVInvokedUrlCommand *) command {
    NSDictionary * musicControlsInfoDict = [command.arguments objectAtIndex:0];
    MusicControlsInfo * musicControlsInfo = [[MusicControlsInfo alloc] initWithDictionary:musicControlsInfoDict];
    NSNumber * elapsed = [NSNumber numberWithDouble:[musicControlsInfo elapsed]];
    NSNumber * playbackRate = [NSNumber numberWithDouble:[musicControlsInfo isPlaying] ? 1.0 : 0.0];

    if (!NSClassFromString(@"MPNowPlayingInfoCenter")) {
        return;
    }

    MPNowPlayingInfoCenter * nowPlayingCenter = [MPNowPlayingInfoCenter defaultCenter];
    NSMutableDictionary * updatedNowPlayingInfo = [NSMutableDictionary dictionaryWithDictionary:nowPlayingCenter.nowPlayingInfo];

    [updatedNowPlayingInfo setObject:elapsed forKey:MPNowPlayingInfoPropertyElapsedPlaybackTime];
    [updatedNowPlayingInfo setObject:playbackRate forKey:MPNowPlayingInfoPropertyPlaybackRate];
    nowPlayingCenter.nowPlayingInfo = updatedNowPlayingInfo;

    // En iOS 14+, asegurarnos de que AirPlay reciba la actualización
    if (@available(iOS 14.0, *)) {
        [self forceNowPlayingInfoRefresh];
    }
}

// this was performing the full function of updateIsPlaying and just adding elapsed time update as well
// moved the elapsed update into updateIsPlaying and made this just pass through to reduce code duplication
- (void) updateElapsed: (CDVInvokedUrlCommand *) command {
    [self updateIsPlaying:(command)];
}

- (void) destroy: (CDVInvokedUrlCommand *) command {
    [self deregisterMusicControlsEventListener];
}

- (void) watch: (CDVInvokedUrlCommand *) command {
    [self setLatestEventCallbackId:command.callbackId];
}

- (MPMediaItemArtwork *) createCoverArtwork: (NSString *) coverUri {
    UIImage * coverImage = nil;

    if (coverUri == nil) {
        return nil;
    }

    if ([coverUri hasPrefix:@"http://"] || [coverUri hasPrefix:@"https://"]) {
        NSURL * coverImageUrl = [NSURL URLWithString:coverUri];
        NSData * coverImageData = [NSData dataWithContentsOfURL: coverImageUrl];

        coverImage = [UIImage imageWithData: coverImageData];
    }
    else if ([coverUri hasPrefix:@"file://"]) {
        NSString * fullCoverImagePath = [coverUri stringByReplacingOccurrencesOfString:@"file://" withString:@""];

        if ([[NSFileManager defaultManager] fileExistsAtPath: fullCoverImagePath]) {
            coverImage = [[UIImage alloc] initWithContentsOfFile: fullCoverImagePath];
        }
    }
    else if (![coverUri isEqual:@""]) {
        NSString * baseCoverImagePath = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) objectAtIndex:0];
        NSString * fullCoverImagePath = [NSString stringWithFormat:@"%@%@", baseCoverImagePath, coverUri];

        if ([[NSFileManager defaultManager] fileExistsAtPath:fullCoverImagePath]) {
            coverImage = [UIImage imageNamed:fullCoverImagePath];
        }
    }
    else {
        coverImage = [UIImage imageNamed:@"none"];
    }

    return [self isCoverImageValid:coverImage] ? [[MPMediaItemArtwork alloc] initWithImage:coverImage] : nil;
}

- (bool) isCoverImageValid: (UIImage *) coverImage {
    return coverImage != nil && ([coverImage CIImage] != nil || [coverImage CGImage] != nil);
}

//Handle seeking with the progress slider on lockscreen or control center
- (MPRemoteCommandHandlerStatus)changedThumbSliderOnLockScreen:(MPChangePlaybackPositionCommandEvent *)event {
    NSString * seekTo = [NSString stringWithFormat:@"{\"message\":\"music-controls-seek-to\",\"position\":\"%f\"}", event.positionTime];
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:seekTo];
    pluginResult.associatedObject = @{@"position":[NSNumber numberWithDouble: event.positionTime]};
    [self.commandDelegate sendPluginResult:pluginResult callbackId:[self latestEventCallbackId]];
    return MPRemoteCommandHandlerStatusSuccess;
}

//Handle the skip forward event
- (MPRemoteCommandHandlerStatus) skipForwardEvent:(MPSkipIntervalCommandEvent *)event {
    NSString * action = @"music-controls-skip-forward";
    NSString * jsonAction = [NSString stringWithFormat:@"{\"message\":\"%@\"}", action];
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:jsonAction];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:[self latestEventCallbackId]];
    return MPRemoteCommandHandlerStatusSuccess;
}

//Handle the skip backward event
- (MPRemoteCommandHandlerStatus) skipBackwardEvent:(MPSkipIntervalCommandEvent *)event {
    NSString * action = @"music-controls-skip-backward";
    NSString * jsonAction = [NSString stringWithFormat:@"{\"message\":\"%@\"}", action];
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:jsonAction];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:[self latestEventCallbackId]];
    return MPRemoteCommandHandlerStatusSuccess;
}

//If MPRemoteCommandCenter is enabled for any function we must enable it for all and register a handler
//So if we want to use the new scrubbing support in the lock screen we must implement dummy handlers
//for those functions that we already deal with through notifications (play, pause, skip etc)
//otherwise those remote control actions will be disabled
- (MPRemoteCommandHandlerStatus) remoteEvent:(MPRemoteCommandEvent *)event {
    return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus) nextTrackEvent:(MPRemoteCommandEvent *)event {
    NSString * action = @"music-controls-next";
    NSString * jsonAction = [NSString stringWithFormat:@"{\"message\":\"%@\"}", action];
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:jsonAction];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:[self latestEventCallbackId]];
    return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus) prevTrackEvent:(MPRemoteCommandEvent *)event {
    NSString * action = @"music-controls-previous";
    NSString * jsonAction = [NSString stringWithFormat:@"{\"message\":\"%@\"}", action];
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:jsonAction];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:[self latestEventCallbackId]];
    return MPRemoteCommandHandlerStatusSuccess;

}

- (MPRemoteCommandHandlerStatus) pauseEvent:(MPRemoteCommandEvent *)event {
    NSString * action = @"music-controls-pause";
    NSString * jsonAction = [NSString stringWithFormat:@"{\"message\":\"%@\"}", action];
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:jsonAction];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:[self latestEventCallbackId]];

    // Si el evento viene de AirPlay, registrarlo específicamente
    if ([event.command isKindOfClass:[MPRemoteCommand class]] &&
        [NSStringFromClass([event.command class]) containsString:@"Extern"]) {
        NSLog(@"🔊 Pausa recibida desde AirPlay");
    }

    return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus) playEvent:(MPRemoteCommandEvent *)event {
    NSString * action = @"music-controls-play";
    NSString * jsonAction = [NSString stringWithFormat:@"{\"message\":\"%@\"}", action];
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:jsonAction];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:[self latestEventCallbackId]];

    // Si el evento viene de AirPlay, registrarlo específicamente
    if ([event.command isKindOfClass:[MPRemoteCommand class]] &&
        [NSStringFromClass([event.command class]) containsString:@"Extern"]) {
        NSLog(@"🔊 Play recibido desde AirPlay");
    }

    return MPRemoteCommandHandlerStatusSuccess;
}

//Handle all other remote control events
- (void) handleMusicControlsNotification: (NSNotification *) notification {
    UIEvent * receivedEvent = notification.object;

    if ([self latestEventCallbackId] == nil) {
        return;
    }

    if (receivedEvent.type == UIEventTypeRemoteControl) {
        NSString * action;

        switch (receivedEvent.subtype) {
            case UIEventSubtypeRemoteControlTogglePlayPause:
                action = @"music-controls-toggle-play-pause";
                break;

            case UIEventSubtypeRemoteControlPlay:
                action = @"music-controls-play";
                break;

            case UIEventSubtypeRemoteControlPause:
                action = @"music-controls-pause";
                break;

            case UIEventSubtypeRemoteControlPreviousTrack:
                action = @"music-controls-previous";
                break;

            case UIEventSubtypeRemoteControlNextTrack:
                action = @"music-controls-next";
                break;

            case UIEventSubtypeRemoteControlStop:
                action = @"music-controls-destroy";
                break;

            default:
                action = nil;
                break;
        }

        if(action == nil){
            return;
        }

        NSString * jsonAction = [NSString stringWithFormat:@"{\"message\":\"%@\"}", action];
        CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:jsonAction];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:[self latestEventCallbackId]];
    }
}

// MÉTODOS PARA SOPORTE DE AIRPLAY

// Registrar observadores específicos para AirPlay
- (void)registerAirPlayObservers {
    // Observar cambios en la ruta de audio (cuando se conecta/desconecta AirPlay)
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(handleAudioRouteChange:)
                                                 name:AVAudioSessionRouteChangeNotification
                                               object:nil];

    // Observar el estado de reproducción externa específicamente para AirPlay
    if (@available(iOS 14.0, *)) {
        [[NSNotificationCenter defaultCenter] addObserver:self
                                                selector:@selector(handleAVPlayerItemDidPlayToEndTime:)
                                                    name:AVPlayerItemDidPlayToEndTimeNotification
                                                  object:nil];

        [[NSNotificationCenter defaultCenter] addObserver:self
                                                selector:@selector(handleAVPlayerItemFailedToPlayToEndTime:)
                                                    name:AVPlayerItemFailedToPlayToEndTimeNotification
                                                  object:nil];

        // Específico para cambios en la reproducción externa
        [[NSNotificationCenter defaultCenter] addObserver:self
                                                selector:@selector(handleAVPlayerExternalPlaybackActive:)
                                                    name:@"AVPlayerExternalPlaybackActiveDidChangeNotification"
                                                  object:nil];
    }

    NSLog(@"✅ Observadores AirPlay registrados correctamente");
}

// Maneja los cambios en la ruta de audio (cuando cambia a/desde AirPlay)
- (void)handleAudioRouteChange:(NSNotification *)notification {
    NSNumber *reasonValue = [notification.userInfo objectForKey:AVAudioSessionRouteChangeReasonKey];
    AVAudioSessionRouteChangeReason reason = [reasonValue unsignedIntegerValue];
    NSLog(@"🔄 Cambio detectado en la ruta de audio, razón: %lu", (unsigned long)reason);

    // Detectar cuando se conecta a AirPlay u otro dispositivo externo
    if (reason == AVAudioSessionRouteChangeReasonNewDeviceAvailable ||
        reason == AVAudioSessionRouteChangeReasonCategoryChange ||
        reason == AVAudioSessionRouteChangeReasonOverride) {

        // Registrar nueva ruta
        AVAudioSessionRouteDescription *currentRoute = [[AVAudioSession sharedInstance] currentRoute];
        for (AVAudioSessionPortDescription *output in [currentRoute outputs]) {
            NSLog(@"🎧 Dispositivo de salida activo: %@ (Tipo: %@)", [output portName], [output portType]);

            if ([[output portType] isEqualToString:AVAudioSessionPortAirPlay]) {
                NSLog(@"🎧 Ruta de audio cambiada a AirPlay, actualizando información de reproducción");

                dispatch_async(dispatch_get_main_queue(), ^{
                    [self refreshNowPlayingInfoForAirPlay];
                });
            }
        }
    }
}

// Actualiza la información de reproducción específicamente para AirPlay
- (void)refreshNowPlayingInfoForAirPlay {
    if (musicControlsSettings) {
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        if (!center.nowPlayingInfo) return;

        NSMutableDictionary *updatedInfo = [NSMutableDictionary dictionaryWithDictionary:center.nowPlayingInfo];

        // Cargar artwork nuevamente
        [self.commandDelegate runInBackground:^{
            [self setCoverArtworkAsync:[musicControlsSettings cover] completion:^(MPMediaItemArtwork *artwork) {
                if (artwork) {
                    updatedInfo[MPMediaItemPropertyArtwork] = artwork;
                }

                // Asegurar que todos los metadatos estén presentes
                updatedInfo[MPMediaItemPropertyArtist] = [musicControlsSettings artist];
                updatedInfo[MPMediaItemPropertyTitle] = [musicControlsSettings track];
                updatedInfo[MPMediaItemPropertyAlbumTitle] = [musicControlsSettings album];

                // Metadatos específicos para iOS 14+ y AirPlay
                if (@available(iOS 14.0, *)) {
                    updatedInfo[MPNowPlayingInfoPropertyIsLiveStream] = @(NO);
                    updatedInfo[MPNowPlayingInfoPropertyMediaType] = @(MPNowPlayingInfoMediaTypeAudio);

                    // Esta propiedad es crucial para la identificación en AirPlay
                    NSString *uniqueIdentifier = [NSString stringWithFormat:@"%@-%@-%@",
                                                [musicControlsSettings artist],
                                                [musicControlsSettings track],
                                                [musicControlsSettings album]];

                    // Usar un identificador único pero estable para AirPlay
                    NSUInteger hashValue = [uniqueIdentifier hash];
                    NSNumber *persistentID = [NSNumber numberWithUnsignedLongLong:(uint64_t)hashValue];
                    updatedInfo[MPMediaItemPropertyPersistentID] = persistentID;
                }

                // Actualizar la información en el hilo principal
                dispatch_async(dispatch_get_main_queue(), ^{
                    center.nowPlayingInfo = updatedInfo;
                    NSLog(@"✅ NowPlayingInfo actualizado específicamente para AirPlay");
                });
            }];
        }];
    }
}

// Manejar cambios en el estado de reproducción externa (AirPlay)
- (void)handleAVPlayerExternalPlaybackActive:(NSNotification *)notification {
    AVPlayer *player = notification.object;

    if (player.isExternalPlaybackActive) {
        NSLog(@"📱➡️🔊 Reproducción externa (AirPlay) está ahora activa");
        dispatch_async(dispatch_get_main_queue(), ^{
            [self refreshNowPlayingInfoForAirPlay];
        });
    } else {
        NSLog(@"🔊➡️📱 Reproducción externa (AirPlay) está ahora inactiva");
    }
}

// Manejar notificaciones específicas de AVPlayer
- (void)handleAVPlayerItemDidPlayToEndTime:(NSNotification *)notification {
    NSLog(@"🎵 AVPlayerItem terminó la reproducción");
}

- (void)handleAVPlayerItemFailedToPlayToEndTime:(NSNotification *)notification {
    NSError *error = [[notification userInfo] objectForKey:AVPlayerItemFailedToPlayToEndTimeErrorKey];
    NSLog(@"❌ Error en reproducción de AVPlayerItem: %@", [error localizedDescription]);
}

//There are only 3 button slots available so next/prev track and skip forward/back cannot both be enabled
//skip forward/back will take precedence if both are enabled
- (void) registerMusicControlsEventListener {
    [[UIApplication sharedApplication] beginReceivingRemoteControlEvents];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(handleMusicControlsNotification:) name:@"musicControlsEventNotification" object:nil];

    // Registrar observadores específicos para AirPlay
    [self registerAirPlayObservers];

    //register required event handlers for standard controls
    MPRemoteCommandCenter *commandCenter = [MPRemoteCommandCenter sharedCommandCenter];

    // Configuración específica para AirPlay en iOS 14+
    if (@available(iOS 14.0, *)) {
        // Habilitar comandos específicos para mejorar interoperabilidad con AirPlay
        commandCenter.togglePlayPauseCommand.enabled = YES;
        [commandCenter.togglePlayPauseCommand addTarget:self action:@selector(togglePlayPauseEvent:)];
    }

    [commandCenter.playCommand setEnabled:true];
    [commandCenter.playCommand addTarget:self action:@selector(playEvent:)];
    [commandCenter.pauseCommand setEnabled:true];
    [commandCenter.pauseCommand addTarget:self action:@selector(pauseEvent:)];
    if(musicControlsSettings.hasNext){
        [commandCenter.nextTrackCommand setEnabled:true];
        [commandCenter.nextTrackCommand addTarget:self action:@selector(nextTrackEvent:)];
    }
    if(musicControlsSettings.hasPrev){
        [commandCenter.previousTrackCommand setEnabled:true];
        [commandCenter.previousTrackCommand addTarget:self action:@selector(prevTrackEvent:)];
    }

    //Some functions are not available in earlier versions
    if(floor(NSFoundationVersionNumber) > NSFoundationVersionNumber_iOS_9_0){
        if(musicControlsSettings.hasSkipForward){
            commandCenter.skipForwardCommand.preferredIntervals = @[@(musicControlsSettings.skipForwardInterval)];
            [commandCenter.skipForwardCommand setEnabled:true];
            [commandCenter.skipForwardCommand addTarget: self action:@selector(skipForwardEvent:)];
        } else {
            if (floor(NSFoundationVersionNumber) > NSFoundationVersionNumber_iOS_9_0) {
                [commandCenter.skipForwardCommand removeTarget:self];
            }
        }
        if(musicControlsSettings.hasSkipBackward){
            commandCenter.skipBackwardCommand.preferredIntervals = @[@(musicControlsSettings.skipBackwardInterval)];
            [commandCenter.skipBackwardCommand setEnabled:true];
            [commandCenter.skipBackwardCommand addTarget: self action:@selector(skipBackwardEvent:)];
        } else {
            if (floor(NSFoundationVersionNumber) > NSFoundationVersionNumber_iOS_9_0) {
                [commandCenter.skipBackwardCommand removeTarget:self];
            }
        }
        if(musicControlsSettings.hasScrubbing){
            [commandCenter.changePlaybackPositionCommand setEnabled:true];
            [commandCenter.changePlaybackPositionCommand addTarget:self action:@selector(changedThumbSliderOnLockScreen:)];
        } else {
            if (floor(NSFoundationVersionNumber) > NSFoundationVersionNumber_iOS_9_0) {
                [commandCenter.changePlaybackPositionCommand setEnabled:false];
                [commandCenter.changePlaybackPositionCommand removeTarget:self action:NULL];
            }
        }
    }

}

// Manejar el evento de togglePlayPause específicamente para iOS 14+
- (MPRemoteCommandHandlerStatus)togglePlayPauseEvent:(MPRemoteCommandEvent *)event {
    NSString *action = @"music-controls-toggle-play-pause";
    NSString *jsonAction = [NSString stringWithFormat:@"{\"message\":\"%@\"}", action];
    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:jsonAction];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:[self latestEventCallbackId]];

    NSLog(@"🔄 Evento toggle play/pause recibido (posiblemente desde AirPlay)");
    return MPRemoteCommandHandlerStatusSuccess;
}

- (void) deregisterMusicControlsEventListener {
    [[UIApplication sharedApplication] endReceivingRemoteControlEvents];
    [[NSNotificationCenter defaultCenter] removeObserver:self name:@"musicControlsEventNotification" object:nil];

    // Eliminar todos los observadores de AirPlay y reproducción externa
    [[NSNotificationCenter defaultCenter] removeObserver:self name:AVAudioSessionRouteChangeNotification object:nil];
    [[NSNotificationCenter defaultCenter] removeObserver:self name:AVPlayerItemDidPlayToEndTimeNotification object:nil];
    [[NSNotificationCenter defaultCenter] removeObserver:self name:AVPlayerItemFailedToPlayToEndTimeNotification object:nil];
    [[NSNotificationCenter defaultCenter] removeObserver:self name:@"AVPlayerExternalPlaybackActiveDidChangeNotification" object:nil];

        MPRemoteCommandCenter *commandCenter = [MPRemoteCommandCenter sharedCommandCenter];
        [commandCenter.playCommand removeTarget:self];
        [commandCenter.pauseCommand removeTarget:self];
        [commandCenter.nextTrackCommand removeTarget:self];
        [commandCenter.previousTrackCommand removeTarget:self];
        [commandCenter.togglePlayPauseCommand removeTarget:self];

        if (floor(NSFoundationVersionNumber) > NSFoundationVersionNumber_iOS_9_0) {
            [commandCenter.changePlaybackPositionCommand setEnabled:false];
            [commandCenter.changePlaybackPositionCommand removeTarget:self action:NULL];
            [commandCenter.skipForwardCommand removeTarget:self];
            [commandCenter.skipBackwardCommand removeTarget:self];
        }

    }

    - (void) dealloc {
        [self deregisterMusicControlsEventListener];
    }

    - (void)setCoverArtworkAsync:(NSString *)coverUri completion:(void (^)(MPMediaItemArtwork *))completion {
        if (!coverUri) {
            completion(nil);
            return;
        }

        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
            __block UIImage *coverImage = nil;

            if ([coverUri hasPrefix:@"http://"] || [coverUri hasPrefix:@"https://"]) {
                NSURL *coverImageUrl = [NSURL URLWithString:coverUri];

                NSURLRequest *request = [NSURLRequest requestWithURL:coverImageUrl
                                                        cachePolicy:NSURLRequestReloadIgnoringLocalAndRemoteCacheData
                                                    timeoutInterval:10.0];

                NSURLSessionDataTask *downloadTask = [[NSURLSession sharedSession] dataTaskWithRequest:request
                                                              completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
                    if (data && !error) {
                        UIImage *image = [UIImage imageWithData:data];
                        if (image) {
                            MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size
                                                                                         requestHandler:^UIImage * _Nonnull(CGSize size) {
                                return image;
                            }];
                            completion(artwork);
                        } else {
                            NSLog(@"❌ No se pudo crear imagen desde los datos descargados");
                            completion(nil);
                        }
                    } else {
                        NSLog(@"❌ Error descargando artwork: %@", error.localizedDescription);
                        completion(nil);
                    }
                }];
                [downloadTask resume];
            } else if ([coverUri hasPrefix:@"file://"]) {
                NSString *fullCoverImagePath = [coverUri stringByReplacingOccurrencesOfString:@"file://" withString:@""];
                if ([[NSFileManager defaultManager] fileExistsAtPath:fullCoverImagePath]) {
                    coverImage = [[UIImage alloc] initWithContentsOfFile:fullCoverImagePath];
                }
                if (coverImage) {
                    MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc] initWithBoundsSize:coverImage.size
                                                                                 requestHandler:^UIImage * _Nonnull(CGSize size) {
                        return coverImage;
                    }];
                    completion(artwork);
                } else {
                    completion(nil);
                }
            } else {
                NSString *baseCoverImagePath = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) objectAtIndex:0];
                NSString *fullCoverImagePath = [NSString stringWithFormat:@"%@%@", baseCoverImagePath, coverUri];
                if ([[NSFileManager defaultManager] fileExistsAtPath:fullCoverImagePath]) {
                    coverImage = [UIImage imageNamed:fullCoverImagePath];
                }
                if (coverImage) {
                    MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc] initWithBoundsSize:coverImage.size
                                                                                 requestHandler:^UIImage * _Nonnull(CGSize size) {
                        return coverImage;
                    }];
                    completion(artwork);
                } else {
                    completion(nil);
                }
            }
        });
    }

    @end
