# ManyEyes (MVP)

Remote Multi-Device Camera + Audio Monitoring System

Architecture Update: There is NO separate admin panel. Every Android user installing the app and logging in with the SAME email automatically can request and view any other device's camera and microphone stream. All devices are peers: they can both publish (camera + mic) and subscribe (view/listen) to others. On first launch the app requests camera and microphone permission explicitly for transparency and ethical operation.

Apps:

- backend: Node/Express + MongoDB + WebSocket signaling
- android_app: Native Android Kotlin app (stream + view others)

High-level:

- Devices that log in with the same email form a private group
- Any device can request another device to start camera/mic (WebSocket command)
- WebRTC used for low-latency audio/video between devices
- Foreground service + persistent notification while streaming for visible, ethical monitoring
- Permissions (CAMERA, RECORD_AUDIO, FOREGROUND_SERVICE, POST_NOTIFICATIONS) prompted on first run

Next Steps:

1. Implement backend auth + device presence + signaling endpoints
2. Implement Android app with login, device list, request stream, show remote streams, start/stop + camera switch
3. Integrate WebRTC offer/answer + ICE relay via backend WebSocket
4. Add simple test pattern stream fallback if camera unavailable

See backend folder for API details once scaffolded.
