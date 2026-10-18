# Resonance Hi-Res Android (alpha scaffold)

A native Android starter project for local audio playback and Wi-Fi playback to Sonos via UPnP AVTransport.

## Implemented in source
- Android file picker for audio files and local playback using AndroidX Media3 ExoPlayer.
- Copies the selected track to app cache, then exposes it through a small LAN HTTP server.
- HTTP server supports `GET`, `HEAD`, and a single byte-range request.
- SSDP discovery for Sonos ZonePlayer devices; reads the device description to find AVTransport control URL.
- Sends `SetAVTransportURI`, `Play`, and `Stop` SOAP commands.
- Simple native UI with status messages.

## Important limitations
- This source has **not** been built or tested on a real Android device in the current environment. There is no Android SDK or Gradle executable installed here, so no verified APK is included.
- Sonos discovery/playback is a first implementation and must be tested on the user's actual Sonos model and home router. Some routers isolate Wi-Fi clients or block multicast.
- UPnP metadata/protocolInfo may need adjustments for specific Sonos firmware and file formats. The current implementation uses `application/octet-stream` metadata and does not negotiate the receiver's supported MIME types.
- The local HTTP server binds to all interfaces on an ephemeral port while the app is running. It serves only the selected track but has no authentication; use only on a trusted home network.
- The selected track is copied to cache, which uses additional storage and is deleted by Android when cache is cleared.
- This does not yet prove bit-perfect output. Android local playback is via ExoPlayer/Android audio path; Sonos processing and volume/EQ may alter output. This is a file-streaming baseline, not a validated bit-perfect chain.
- Not yet implemented: library scanning, playlists, gapless playback, ReplayGain, EQ/DSP, USB DAC exclusive output, DSD/DoP, sample-rate negotiation, grouping/multi-room UI, cover-art metadata, queue management, background playback service, and automated tests.

## Build locally
1. Install Android Studio with Android SDK Platform 35 and a compatible JDK (JDK 17 is normally suitable for Android Gradle Plugin 8.7.x).
2. Open this folder in Android Studio and let Gradle sync.
3. Build > Build APK(s). If sync/build errors occur, resolve them before installing.
4. Install on an Android phone connected to the same non-isolated Wi-Fi network as Sonos.
5. Allow the app to use the local network if prompted, choose a track, discover Sonos, and send the track.

Package: `com.resonance.hires`
Version: `0.1.0-alpha`
