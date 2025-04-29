#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#import <Cordova/CDV.h>

@interface CDVAudioSessionManager : NSObject

/**
 * Obtiene la instancia compartida del gestor de sesiones de audio
 */
+ (instancetype)sharedInstance;

/**
 * Configura la sesión de audio para reproducción optimizada para AirPlay y controles multimedia
 * @return YES si la configuración fue exitosa, NO en caso contrario
 */
- (BOOL)setupAudioSessionForPlayback;

/**
 * Activa la sesión de audio
 * @return YES si la activación fue exitosa, NO en caso contrario
 */
- (BOOL)activateAudioSession;

/**
 * Desactiva la sesión de audio
 * @return YES si la desactivación fue exitosa, NO en caso contrario
 */
- (BOOL)deactivateAudioSession;

/**
 * Obtiene el error más reciente, si existe
 */
- (NSError *)lastError;

@end