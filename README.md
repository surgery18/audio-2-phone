# audio-2-phone

MVP for sending Windows system audio to an Android phone on the same local network.

## What is included

- `windows-streamer/Audio2Phone.Streamer`: .NET Windows tray app that captures default output audio through WASAPI loopback, answers LAN discovery, and prompts for pairing approval.
- `android-receiver`: native Android app that discovers PCs on the LAN, pairs with a six-digit confirmation code, and plays the stream through a foreground service.

The stream is local-network TCP. Discovery uses UDP broadcast on port `4041`; audio uses TCP port `4040`.

## Run the Windows streamer

Requirements:

- Windows
- .NET 7 SDK or newer

From PowerShell:

```powershell
cd "$env:USERPROFILE\Desktop\audio-2-phone\windows-streamer"
.\run-streamer.ps1
```

The app starts in the system tray and begins listening automatically. Right-click the tray icon to:

- start or stop streaming
- show network info
- exit the app

If Windows Firewall prompts you, allow private-network access.

## Build the Android app

Requirements:

- Android Studio or Android SDK plus Gradle
- Android phone on the same Wi-Fi as the PC

Open `android-receiver` in Android Studio and run the `app` configuration on your phone. Tap `Find computers`, select the Windows PC, then compare the six-digit code shown on the phone with the Windows popup. Click Yes on Windows if the code matches.

Playback runs in a foreground service with a notification and partial wake lock, so it should continue when the phone screen turns off.

Command-line build, when Android tooling is installed:

```powershell
cd "$env:USERPROFILE\Desktop\audio-2-phone\android-receiver"
gradle assembleDebug
```

## Notes

- This MVP intentionally avoids a virtual audio driver. It captures the current default Windows playback device.
- It converts the captured audio to 16-bit PCM and sends mono or stereo depending on the Windows mix format.
- Latency depends mostly on Wi-Fi quality and Android playback buffering.
- For production use, the next steps would be encrypted transport, QR pairing, reconnect handling, device discovery, and an installer.
