#import "CDVAudioSessionManager.h"

@interface CDVAudioSessionManager ()
@property (nonatomic, strong) NSError *lastError;
@end

@implementation CDVAudioSessionManager

static CDVAudioSessionManager *sharedInstance = nil;

+ (instancetype)sharedInstance {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        sharedInstance = [[self alloc] init];
    });
    return sharedInstance;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        // Suscribirse a notificaciones de interrupción
        [[NSNotificationCenter defaultCenter] addObserver:self
                                                 selector:@selector(handleAudioSessionInterruption:)
                                                     name:AVAudioSessionInterruptionNotification
                                                   object:nil];
        
        // Suscribirse a notificaciones de cambio de ruta
        [[NSNotificationCenter defaultCenter] addObserver:self
                                                 selector:@selector(handleAudioRouteChange:)
                                                     name:AVAudioSessionRouteChangeNotification
                                                   object:nil];
    }
    return self;
}

- (BOOL)setupAudioSessionForPlayback {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    
    // Primero establecer la categoría para reproducción de audio
    self.lastError = nil;
    BOOL success = [session setCategory:AVAudioSessionCategoryPlayback 
                                  error:&_lastError];
    
    if (!success) {
        NSLog(@"❌ Error estableciendo categoría AVAudioSession: %@", [self.lastError localizedDescription]);
        return NO;
    }
    
    // Configurar la política de compartición de ruta (iOS 13+)
    if (@available(iOS 13.0, *)) {
        @try {
            if ([session respondsToSelector:@selector(setRouteSharePolicy:error:)]) {
                success = [session setRouteSharePolicy:1 /* AVAudioSessionRouteSharingPolicyLongFormAudio */ 
                                                error:&_lastError];
                if (!success) {
                    NSLog(@"❌ Error estableciendo política de compartición de ruta: %@", [self.lastError localizedDescription]);
                } else {
                    NSLog(@"✅ Política de compartición de ruta configurada correctamente");
                }
            } else {
                // Fallback para dispositivos que no soportan el selector directamente
                @try {
                    [session setValue:@(1) forKey:@"routeSharingPolicy"];
                    NSLog(@"✅ Política de compartición de ruta configurada via KVC");
                } @catch (NSException *exception) {
                    NSLog(@"❌ Error configurando política de compartición via KVC: %@", exception.reason);
                    success = NO;
                }
            }
        } @catch (NSException *exception) {
            NSLog(@"❌ Excepción configurando política de compartición: %@", exception.reason);
            success = NO;
        }
    }
    
    // Configurar opciones para que funcione mejor con AirPlay
    if (@available(iOS 10.0, *)) {
        success = [session setCategory:AVAudioSessionCategoryPlayback
                           withOptions:AVAudioSessionCategoryOptionAllowAirPlay
                                 error:&_lastError];
        if (!success) {
            NSLog(@"❌ Error configurando opciones de categoría: %@", [self.lastError localizedDescription]);
        }
    }
    
    return success;
}

- (BOOL)activateAudioSession {
    self.lastError = nil;
    BOOL success = [[AVAudioSession sharedInstance] setActive:YES error:&_lastError];
    if (!success) {
        NSLog(@"❌ Error activando AVAudioSession: %@", [self.lastError localizedDescription]);
    } else {
        NSLog(@"✅ AVAudioSession activado correctamente");
    }
    return success;
}

- (BOOL)deactivateAudioSession {
    self.lastError = nil;
    BOOL success = [[AVAudioSession sharedInstance] setActive:NO error:&_lastError];
    if (!success) {
        NSLog(@"❌ Error desactivando AVAudioSession: %@", [self.lastError localizedDescription]);
    }
    return success;
}

- (void)handleAudioSessionInterruption:(NSNotification *)notification {
    NSDictionary *userInfo = notification.userInfo;
    NSUInteger type = [[userInfo objectForKey:AVAudioSessionInterruptionTypeKey] unsignedIntegerValue];
    
    if (type == AVAudioSessionInterruptionTypeBegan) {
        // La sesión de audio ha sido interrumpida, por ejemplo, por una llamada telefónica
        NSLog(@"🔔 Interrupción de la sesión de audio iniciada");
        
        // Enviamos notificación para que otros plugins puedan reaccionar
        [[NSNotificationCenter defaultCenter] postNotificationName:@"CDVAudioSessionInterruptionBegan" 
                                                            object:self 
                                                          userInfo:userInfo];
    } else if (type == AVAudioSessionInterruptionTypeEnded) {
        // La interrupción ha terminado
        NSLog(@"🔔 Interrupción de la sesión de audio finalizada");
        
        // Intentamos reactivar automáticamente la sesión
        NSUInteger options = [[userInfo objectForKey:AVAudioSessionInterruptionOptionKey] unsignedIntegerValue];
        if (options == AVAudioSessionInterruptionOptionShouldResume) {
            [self setupAudioSessionForPlayback];
            [self activateAudioSession];
        }
        
        // Enviamos notificación para que otros plugins puedan reaccionar
        [[NSNotificationCenter defaultCenter] postNotificationName:@"CDVAudioSessionInterruptionEnded" 
                                                            object:self 
                                                          userInfo:userInfo];
    }
}

- (void)handleAudioRouteChange:(NSNotification *)notification {
    NSDictionary *userInfo = notification.userInfo;
    NSUInteger reason = [[userInfo objectForKey:AVAudioSessionRouteChangeReasonKey] unsignedIntegerValue];
    
    NSLog(@"🔔 Cambio en la ruta de audio. Razón: %lu", (unsigned long)reason);
    
    // Enviamos notificación para que otros plugins puedan reaccionar
    [[NSNotificationCenter defaultCenter] postNotificationName:@"CDVAudioRouteChanged" 
                                                        object:self 
                                                      userInfo:userInfo];
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

@end