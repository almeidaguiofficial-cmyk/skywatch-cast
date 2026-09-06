# Skywatch Cast

App Android que transmite a **tela do celular por RTMP**, sem limite de tempo — feito para
mandar o feed do drone (Fimi X8, DJI, etc.) ao vivo para o **Skywatch**.

Baseado na biblioteca [RootEncoder](https://github.com/pedroSG94/RootEncoder) (captura de tela
nativa via `MediaProjection` + encode H.264 + push RTMP), rodando dentro de um *foreground
service* — por isso continua transmitindo mesmo com o app do drone na frente.

## Como usar

1. Instale o APK (`app-debug.apk`).
2. Abra o app e preencha o campo **Endereço RTMP** com o endereço do seu servidor,
   no formato `rtmp://SEU-SERVIDOR:1935/fimi`. O app lembra o valor.
3. Deixe **"Enviar áudio do microfone" desmarcado** (transmite só vídeo).
4. Abra o **Fimi Navi** e conecte no drone.
5. Volte ao Skywatch Cast → **Transmitir** → aceite "capturar a tela".
6. Tudo que aparecer na tela vai ao vivo pro Skywatch. Para encerrar: **Parar**.

> Se o vídeo **não aparecer** no Skywatch (caso o MediaMTX exija faixa de áudio),
> marque **"Enviar áudio do microfone"** e transmita de novo.

## Perfil de transmissão (padrão)

- 1280x720 landscape, ~2 Mbps, 30 fps, GOP 2s, vídeo-only.
- Para mudar, edite as constantes no topo de
  [`ScreenService.kt`](app/src/main/java/com/skywatch/screencast/ScreenService.kt).

## Build

Push na branch `main` dispara o GitHub Actions, que gera o `app-debug.apk` como
*artifact* e também o publica na release **latest**. Nada de Android Studio necessário.

Para compilar local (se tiver o Android SDK): `gradle assembleDebug`.
