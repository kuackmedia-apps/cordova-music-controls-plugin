# Fork de kuackmedia

Fork de [ghenry22/cordova-plugin-music-controls2](https://github.com/ghenry22/cordova-plugin-music-controls2) (3.0.6):
notificación de reproducción, lockscreen y `MediaSession` del app, y en Android además **el único servicio en primer
plano (FGS) del app** (`MusicControlsNotificationKiller`, tipo `mediaPlayback`).

**`master` es lo publicado**: las 8 apps de producción lo instalan con
`spec="github:kuackmedia-apps/cordova-music-controls-plugin"` (sin versión) desde los `config-app*.xml` de
`_common/configTemplates`. Un push a `master` llega al próximo `initApp.sh` de **todas** las apps. Los cambios se prueban
antes en una rama y, si hace falta, con un tag fijado en un solo template.

## `fgs-grace`: que el app no pierda el FGS en segundo plano (B-07, binario de Claro del 19/10/2026)

Diseño completo, evidencia y plan de prueba:
`apps/_common/.claude/B07-datos/2026-10-13-fgs-background-diseno.md` (§2 regla, §4 nativo N1–N8 y sus desvíos, §5, §6).
Las líneas cambiadas en el código llevan el comentario `kuackmedia (fgs-grace, Nx)`.

### El problema

- El FGS estaba atado al botón de play/pausa: cualquier `updateIsPlaying(false)` hacía `stopForeground(DETACH)` en el
  acto. Casteando, el JS publica `false` en cada cambio de tema.
- En Android 12+ `stopForeground` resetea el permiso de promoción: el `true` siguiente, con el app atrás y sin exención,
  da `ForegroundServiceStartNotAllowedException` ("Background started FGS: Disallowed" en el logcat). El catch vacío se
  lo tragaba y el latch `isForeground` quedaba en `true`: nadie se enteraba.
- Un reload del WebView (`onReset` → `onDestroy`) desligaba el servicio para siempre (R27).

### Máquina de estados (`MusicControlsServiceConnection`)

Todo su estado vive en el **main looper**: los demás hilos (puente de Cordova, thread pool del `create`) sólo encolan.
El JS publica una intención (reproduciendo o no); el nativo decide el FGS.

| Estado     | FGS | Significa                                                                                   |
| ---------- | --- | ------------------------------------------------------------------------------------------- |
| `OFF`      | no  | sin intención de reproducir                                                                 |
| `ON`       | sí  | reproduciendo                                                                               |
| `GRACE`    | sí  | en pausa (o promovido por un comando y todavía sin confirmar); la baja espera `graceMs`     |
| `DEFERRED` | no  | el JS quiere reproducir pero no hay FGS: no hubo exención para promover, o el intento falló |

| Evento                                | Transición                                                                                                                                                                                                                   |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| publish `true`                        | `ON`/`GRACE` → `ON` **sin** `startForeground` (cancela la baja); `OFF`/`DEFERRED` → intento de promoción                                                                                                                     |
| publish `false`                       | `ON` → `GRACE` (baja programada a `graceMs`; con `graceMs` 0, baja ya); `DEFERRED` → `OFF`                                                                                                                                   |
| publish de una página anterior        | se descarta: un pedido de la página que había antes del último reload no cambia nada (generación de página, ver «Orden e hilos»)                                                                                             |
| vence la gracia                       | `stopForeground(DETACH)` → `OFF`: la notificación queda con ▶, que es la puerta legal para reanudar. Si hubo un reload y la página nueva no hizo `create` ni reprodujo: `REMOVE`                                            |
| comando del usuario                   | abre una ventana de 9 s (desde la hora de la tecla si trae un `KeyEvent`); si está `DEFERRED` (o `OFF` con notificación, para play/siguiente/anterior) promueve ya, antes de reenviar al JS                                  |
| la Activity vuelve a verse            | si está `DEFERRED`, promueve                                                                                                                                                                                                 |
| `destroy` (JS o notificación)         | en el main looper, `stopForeground(REMOVE)` y después el `cancel` de la notificación → `OFF`. Si entre el pedido y ese momento se publicó una notificación más nueva (destroy seguido de un `create`): `DETACH` y sin cancel |
| `onReset` (reload)                    | como una pausa: `ON` → `GRACE` y el plugin publica la pausa (▶ en la notificación, sesión `PAUSED`: el reload cortó el audio); sin FGS, la notificación se borra como antes                                                 |
| `onDestroy` real                      | gracia cancelada, `REMOVE` y después `unbindService`                                                                                                                                                                         |
| `setForegroundPolicy({legacy: true})` | kill-switch: la lógica del plugin viejo (ver «Contrato con el JS»); en `GRACE`, baja ya                                                                                                                                      |

Una promoción **sólo se intenta con exención** (regla 2.4 del diseño, verificada contra AOSP 14):

1. SDK < 31 (no hay restricción);
2. la Activity visible (`onStart`…`onStop`) o hasta 3 s después del `onStop`: AOSP da 5 s, pero contados desde antes
   del `onStop`, que en un equipo lento llega tarde;
3. dentro de la ventana de 9 s de un comando: `MediaSessionCallback` (`onPlay`, siguiente, anterior, seek,
   `onMediaButtonEvent` con PLAY, PLAY_PAUSE, NEXT, PREVIOUS o HEADSETHOOK) y `MusicControlsBroadcastReceiver`
   (`music-controls-play/next/previous`, `music-controls-media-button`). AOSP da 10 s a la sesión y las teclas y 30 s
   a los PendingIntent de la notificación. Con una tecla la ventana corre desde `KeyEvent.getEventTime()` (uptime),
   no desde que llegó el callback; los callbacks de la sesión llegan al main looper (`setCallback` con un `Handler` del
   main), no al hilo del puente, que puede estar esperando el lock de la notificación;
4. el proceso en TOP o BOUND_TOP (`ActivityManager.getMyMemoryState`: otra Activity del app al frente);
5. `isIgnoringBatteryOptimizations` ya en `true` **porque el usuario lo eligió**. El plugin no la pide nunca.

Sin exención no se llama a `startForeground`: el estado queda `DEFERRED` (log `why=no-exemption`) y se promueve en la
próxima ventana. Mientras siga así, la exención no se vuelve a consultar en cada publish (`getMyMemoryState` es un
binder): recién ante una oportunidad nueva (comando, la Activity vuelve a verse, bind). **Anti-loop**: el JS publica
cada segundo; después de un intento fallido no se reintenta hasta una oportunidad nueva. Un `bindService` rechazado
tampoco se reintenta en cada publish, sólo ante una oportunidad nueva.

Un comando con el FGS en `OFF` promueve de forma **optimista** y deja el estado en `GRACE`: si el JS confirma
(`updateIsPlaying(true)`) pasa a `ON`; si decide no reproducir (cuota Lite, Station) o no se entera (el callback de
`watch` es de un solo uso y puede estar en null), el FGS cae solo al vencer la gracia. No promueven desde `OFF` (abren
la ventana y promueven sólo si ya estaba `DEFERRED`): el seek (en pausa no es reproducir) y PLAY_PAUSE por el receptor
de botones (`music-controls-media-button-play-pause`), que el JS trata como pausa. Por la sesión, PLAY_PAUSE llega
como `music-controls-toggle-play-pause` y sí promueve.

El reloj de la gracia es un `Handler` del main looper (uptime): con la CPU dormida la gracia se estira, que es el lado
conservador. Es el mismo reloj que usa Media3 (`MSG_USER_ENGAGED_TIMEOUT`, 10 min desde 1.6.0) y corre en un proceso
que está en FGS justamente para no congelarse.

### Resultado verificado (`MusicControlsNotificationKiller.setForeground`)

- Excepción ⇒ `denied` (en SDK 31+ se reconoce `ForegroundServiceStartNotAllowedException` por nombre, así la clase no
  se referencia en equipos viejos). `err` = clase o `not-allowed` + mensaje.
- Sin excepción pero sin FGS ⇒ `ignored` (batería "Restringida", app-op). Hay dos señales: el `foreground` de
  `getRunningServices()` (todavía devuelve los servicios propios: es el `isForeground` del `ServiceRecord`), que manda,
  y `getForegroundServiceType()` en SDK 29+ (verificado en AOSP 14; en 12/13 no), que decide si el servicio no aparece
  en la lista. `err` lleva las dos: `running-service foreground=false type=0`.
- El `stopForeground(DETACH)` defensivo ante `ignored` se hace **sólo si las dos señales coinciden** (lista `false` y
  tipo 0). Con una sola, o en desacuerdo, no se baja: si un OEM reporta mal una de las dos, el stop perdería un FGS
  real. El servicio queda como "puede estar en primer plano" (`mayBeForeground()`) y la máquina lo suelta con un
  `DETACH` al pasar a `OFF` (y el `destroy`, con `REMOVE`), para no dejar un FGS sin dueño.
- Se loguea con `Log.w("KuackFGS", …)`.

### Contrato con el JS (fijo; el JS del app lo implementa igual)

`www/MusicControls.js` suma dos funciones (fuera de Android van directo al error con `"not_supported"`, sin `exec`):

```js
MusicControls.getForegroundState(success, error); // exec("getForegroundState")
MusicControls.setForegroundPolicy({ graceMs: N, legacy: false }, success, error); // las dos claves son opcionales
```

- El success de las dos, y el de `updateIsPlaying` y `create`, es **el mismo JSON string**:
  `{"fgs":"on|grace|off|deferred|denied|ignored|unbound","why":"…","err":"…","sdk":N,"graceMs":N,"legacy":bool}`.
  `fgs` = `unbound` mientras no hay servicio ligado; `deferred` con un fallo previo se reporta como `denied`/`ignored`.
  `why` = qué lo disparó y con qué exención (`create/visible`, `session:play/window:session:play`, `play/legacy`,
  `no-exemption`, `grace-expired`, `grace-expired/remove`, `reset`, `destroy`, `destroy/newer`…). Se responde desde el
  main looper, después de aplicar ese pedido.
- **Detección del binario nuevo**: la función viaja en el binario, pero el `js-module` es común a todas las
  plataformas: `getForegroundState` existe también en iOS (Claro iOS lleva el mismo plugin). El app tiene que gatear
  por las tres condiciones: `isAndroid() && !isAndroidTv() && typeof MusicControls.getForegroundState === 'function'`.
  Con eso apaga sus parches para el binario viejo (O6/O7 del diseño).
- **Binario viejo**: `updateIsPlaying`/`create` devuelven `"success"` (el JS lo ignora) y una acción desconocida
  devuelve `true` sin llamar a ningún callback.
- `graceMs`: default 600000; se acota a [0, 3600000]. **0 sólo revierte la baja** (bajar al instante en una pausa): la
  promoción sigue con la regla de exención, el anti-loop y la promoción optimista apagada; el reload sigue sin desligar
  y el `destroy` usa `REMOVE`. Cambiarlo con el FGS en `GRACE` recalcula la baja desde el inicio de la pausa.
- `legacy: true` es el **kill-switch por OTA** de la lógica de promoción: vuelve a lo del plugin viejo. Se intenta
  `startForeground` sin consultar la exención ni el anti-loop por oportunidad (como el latch viejo: un intento por cada
  borde `false`→`true`, no uno por segundo), se baja al instante en una pausa (sin gracia: `graceMs` queda guardado
  pero no se usa) y no hay promoción optimista por un comando. Se conserva lo que no es lógica de promoción: el
  resultado verificado, el bind con reintento, el reload sin unbind (R27), la generación de página y el `REMOVE` del
  `destroy`. La promoción al volver a verse y en la ventana de un comando con el estado `DEFERRED` se mantienen: tienen
  exención real y no pueden fallar donde el plugin viejo no fallaba. Dura lo que el plugin (un reload no la borra);
  `legacy: false` vuelve a la lógica nueva. El JS del app todavía no la usa: falta su clave de QA.
- No se usa el callback de `watch` para reportar: es de un solo uso y consumiría un comando del usuario.
- iOS no cambia (`src/ios` intacto).
- **Estilo de `www/MusicControls.js`**: es una excepción a la regla de Prettier del workspace. El fork está fuera del
  workspace y el archivo nunca cumplió esa config (estilo Prettier 1.x del upstream: 2 espacios, comillas dobles,
  `function(`); las líneas nuevas siguen el estilo del archivo para no reformatearlo entero.

### Orden de los pedidos e hilos (N4, N6, N8)

- `execute()` numera cada pedido (`mSeq`, en el orden del JS). `updateIsPlaying` guarda el último estado publicado; un
  `create` que termina después (baja la carátula en el thread pool) y es más viejo usa ese estado en lugar del suyo, y
  si ya se aplicó un `create` más nuevo, no se aplica (no pisa metadata ni notificación).
- **Generación de página**: `onReset` (main looper, el único que la escribe) incrementa `pageGen` y `execute()` la
  captura en el hilo del puente. Un `create`, `updateIsPlaying` o `updateDismissable` de la página anterior al reload
  (un `create` que todavía bajaba la carátula) no toca la notificación, la sesión ni el FGS, aunque responde su
  callback. Además, cada publish lleva la generación del pedido y la máquina descarta el de una página anterior (log
  `discard create gen=0<1`). Sin eso, el `create` tardío volvía a `ON` y anulaba el `REMOVE` del reload: un FGS sin
  dueño hasta que muriera el proceso (logout por token durante un cambio de tema).
- **Pausa del reload**: el plugin la publica en el thread pool, porque `updateIsPlaying` espera el lock de la
  notificación, que un `create` puede tener tomado mientras baja la carátula, y el main looper no puede esperarlo. La
  sesión queda `PAUSED` en la posición de ese momento. Se descarta si hubo otro reload o si la página nueva ya publicó.
- **`destroy`**: el `cancel` corre en el main looper después del `REMOVE` (con el FGS en pie un `cancel` no hace nada
  sobre su notificación). La notificación cuenta sus `notify` (`notifyCount`): si entre el pedido y el main looper se
  publicó una más nueva, el FGS baja con `DETACH` y no se cancela, para no borrarla.
- La decisión de FGS nunca corre en el hilo del puente ni en el pool: se encola en el main looper. El `onReset` se
  encola siempre (aun estando en el main looper) para ir después de los publish de la página vieja que ya estaban en la
  cola.
- Descarga de la carátula con `setConnectTimeout(3000)` / `setReadTimeout(5000)` (N7). La reestructuración (descargar
  fuera del lock, una sola descarga) queda para la sesión única del reproductor nativo.

### Cambios por archivo

| Archivo                                | Cambio                                                                                                                                                                                                                                                                                   |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MusicControlsServiceConnection.java`  | reescrito: máquina de estados, regla de exención, generación de página, `legacy`, bind con reintento (`ensureBound`, `onBindingDied`), reporte JSON (N1, N3, N4, N5)                                                                                                                     |
| `MusicControlsNotificationKiller.java` | `int setForeground(Notification)` verificado con dos señales, `mayBeForeground()` y `clearForeground(boolean remove)` (N2)                                                                                                                                                               |
| `MusicControls.java`                   | `seq` y orden (N8); generación de página y pausa del reload (N4); success JSON; acciones `getForegroundState`/`setForegroundPolicy` (N5); `onReset` sin `onDestroy`, `onDestroy` en orden (N4, N6); callbacks de la sesión en el main; `onStart`/`onResume`/`onStop` (N3); timeouts (N7) |
| `MediaSessionCallback.java`            | abre la ventana del comando antes de reenviar al JS, aunque `cb` sea null; las teclas con la hora del `KeyEvent` (N3)                                                                                                                                                                    |
| `MusicControlsBroadcastReceiver.java`  | idem; PLAY_PAUSE por este camino no promueve desde `OFF` (N3)                                                                                                                                                                                                                            |
| `MusicControlsNotification.java`       | hook `onNotificationUpdated(n, source)`; `notifyCount`; `destroy()` pasa el `cancel` a quien suelta el FGS (N6); timeouts (N7)                                                                                                                                                           |
| `www/MusicControls.js`                 | `getForegroundState`, `setForegroundPolicy({ graceMs, legacy })`                                                                                                                                                                                                                         |

No cambian `plugin.xml` (el manifiesto ya declara `mediaPlayback` y `FOREGROUND_SERVICE_MEDIA_PLAYBACK`) ni las
dependencias.

## Cómo probarlo

**Sin dispositivo**: `.claude/test/fgs/run.sh` compila `src/android` contra `android.jar` + androidx (caché de
Gradle) + CordovaLib de un app con la plataforma android agregada (`CORDOVA_JAR=…` para otra ruta). Si falta alguna de
esas dependencias **falla** (exit 2): un exit 0 tiene que probar la compilación; `ALLOW_SKIP=1` la saltea a sabiendas.
Después corre el harness de la máquina de estados: el `MusicControlsServiceConnection.java` real contra stubs mínimos
de Android con reloj virtual y un doble del servicio (70 chequeos). Cubre gracia, `DEFERRED` sin intento y sin
re-consultar la exención, los bordes de la ventana (8,9 s promueve, 9,1 s no), la ventana anclada a la hora de la
tecla, seek en `OFF` y en `DEFERRED`, promoción optimista, anti-loop, reload (pausa publicada, `create` tardío de la
página vieja descartado, `REMOVE`), `destroy` (`REMOVE` y después `cancel`; `DETACH` con una notificación más nueva),
`graceMs`, `legacy`, `ignored` incierto, SDK 30, las exenciones, bind tardío, rebind tras perder el servicio, bind
rechazado sin spam y shutdown. Lo que vive en `MusicControls.java` (N8, la generación de página del lado del puente, la
pausa del reload), el receptor de botones y el Killer real no están en el harness (necesitan las clases de Android):
quedan para la compilación y la QA. Esos archivos no los compila el app (no están en `plugin.xml`).

**En el equipo** (`PKG=com.kuackmedia.claro`):

```bash
adb shell dumpsys activity services $PKG | grep -E "MusicControlsNotificationKiller|isForeground|types="
#   esperado reproduciendo o en gracia: isForeground=true types=0x00000002
adb shell dumpsys activity processes $PKG | grep -E "curProcState|procState"   # 4 = FGS
adb shell cmd package list packages -U $PKG                                       # uid para netpolicy
adb shell dumpsys netpolicy | grep <uid>
adb logcat -v time ActivityManager:* KuackFGS:* *:S | grep -E "FGS|startForeground|KuackFGS"
#   cero "Disallowed" y cero "not allowed"; "why=no-exemption" sólo en los casos sin exención (diseño §5)
```

Simulaciones: comandos `adb shell cmd media_session dispatch play|pause|next`; Doze
`adb shell dumpsys deviceidle force-idle` / `unforce`; batería restringida
`adb shell cmd appops set $PKG RUN_ANY_IN_BACKGROUND ignore` (esperado `ignored`/`denied`, sin crash ni loop); gracia
corta desde el JS con `localStorage.QA_FGS_GRACE_MS=30000` (llama a `setForegroundPolicy`).

Líneas de `KuackFGS` esperables: `bound`, `visible`/`hidden`, `OFF->ON why=create/visible`, `ON->GRACE why=pause`,
`GRACE->ON why=play`, `GRACE->OFF why=grace-expired`, `OFF->DEFERRED why=no-exemption trigger=play`,
`window session:play`, `DEFERRED->ON why=session:play/window:session:play`, `ON->GRACE why=reset`,
`discard create gen=0<1`, `GRACE->OFF why=grace-expired/remove`, `policy graceMs=… legacy=…`, `release uncertain`; y
con `W/`: `denied …`, `ignored …`, `startForeground ignorado: running-service foreground=… type=…`.

Casos del plan de prueba en dispositivos (diseño §6, build B = este binario): 2 (pausa/play desde la TV dentro de la
gracia: `grace`→`on` sin `startForeground`), 2b (más larga que la gracia: `deferred` sin intento; al abrir, `on`),
8 (pausa de 15 min y play desde la notificación: promoción nativa en el callback), 10 (reload sonando: procState 4 se
mantiene y la notificación pasa a ▶) y 11 (batería restringida).

## Publicación

1. Rama `fgs-grace` desde `master` (este worktree: `cordova-music-controls-plugin-fgs`). Push y tag propuesto
   **`v2.2.0-b07`** (los tags previos del fork son `2.1.x`).
2. Fijarlo **sólo** en `_common/configTemplates/config-app-claro.xml:335`:
   `spec="github:kuackmedia-apps/cordova-music-controls-plugin#v2.2.0-b07"`. Lo hace el usuario.
   `config-app.xml` y los demás templates **no se tocan**: `master` sin tag es lo que recibe cada `initApp.sh` de las
   8 apps.
3. Builds locales: `config-app-local.xml` apunta a `/Users/mac/WebstormProjects/cordova-music-controls-plugin` (el
   checkout principal): poner ahí la rama `fgs-grace`, o cambiar el path al del worktree sólo para ese build.
4. Llevarlo a `master` recién con la QA de §6 en verde: desde ese momento lo reciben todas las apps.

## Riesgos y pendientes

- **UX de la gracia**: durante los 10 min de pausa la notificación de un FGS no se puede deslizar en Android ≤ 13 y el
  app figura en el administrador de tareas de FGS de Android 13+ (igual que Media3). En Android 14+ no está verificado
  si se puede descartar; si se puede, el deslizamiento dispara `music-controls-destroy` como hoy en pausa.
- **Reload sin dueño**: tras un reload (p. ej. logout) la notificación del tema anterior queda en pausa (▶) hasta
  10 min antes del `REMOVE`, en lugar de desaparecer al instante.
- **`ignored` en MIUI/HyperOS (riesgo de QA, Redmi A14 y Xiaomi A15)**: si el OEM reporta mal una de las dos señales,
  queda `ignored` (`DEFERRED`) con un FGS que puede estar en pie hasta el próximo paso a `OFF`. Buscar en el logcat
  `startForeground ignorado` con las señales en desacuerdo (`foreground=false type=2` o `foreground=true type=0`) y
  cruzarlo con `dumpsys activity services` (`isForeground`).
- `getForegroundServiceType()` en Android 12/13 no está verificado (por eso manda `getRunningServices`).
- La ventana de 9 s supone que el OEM respeta los 10 s/30 s de AOSP; si un OEM da menos, el intento falla una vez y el
  anti-loop lo frena hasta la próxima oportunidad.
- Reanudar sin interacción del usuario después de la gracia (control remoto de la TV, foco recuperado tras una llamada
  de más de 10 min) sigue sin exención legal en 12+: queda `DEFERRED` y se promueve al volver al app (diseño §5).
- `navigator.app.loadUrl` (Cordova recrea los plugins y llama a `onDestroy`) sigue soltando el FGS; el app usa
  `location.reload()`, que pasa por `onReset`.
- Previo a este arreglo: HEADSETHOOK por la sesión cae en el `default` de `onMediaButtonEvent`, que le manda al JS
  `music-controls-unknown-79` y consume el callback; el `onPlay` que después sintetiza la sesión llega sin callback. Con
  el FGS en `OFF` queda la promoción optimista en `GRACE` hasta que vence.
- Todavía no se probó en un equipo: compila contra android-36 y el harness pasa, pero falta la QA de §6.
