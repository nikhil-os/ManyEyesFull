# Android App (ManyEyes)

Native Kotlin app that can both stream and view other devices when logged in with the same email.

Current status: Scaffold only (login mock, device list placeholder, foreground service stub).

## Next Implementation Steps

1. Implement real login (Retrofit -> backend /auth/login).
2. Persist JWT + deviceId in DataStore.
3. WebSocket signaling client to /ws with token + deviceId.
4. Presence updates render real device list.
5. REQUEST_STREAM flow -> start foreground service, initialize WebRTC, send OFFER.
6. Viewer handles OFFER -> ANSWER -> ICE negotiation.
7. CameraX + WebRTC capturer integration.
8. AudioRecord -> WebRTC audio track.
9. Switch camera message handling.
10. Stop stream message handling.

## Permissions

Requested once at install/start: CAMERA, RECORD_AUDIO. Foreground service shows notification while active.
