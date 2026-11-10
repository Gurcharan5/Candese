# Cadence

Local music player for Android with ReplayGain, crossfade and gapless playback.

## How it works
| Feature |
|---|
| Gapless |
| Crossfade |
| ReplayGain |
| Folder navigation |
| Queue |
| Shuffle |
| Playlists |
| Multi-select |
| Tag editing |
| Home + listening stats |
| Now Playing gradient |
| Notification / watch / Bluetooth controls | 
## Known limitations
- The library index is a cache and may be rebuilt on upgrade. Playlists and listening history are in a separate database and are kept.
- ReplayGain is only applied to 16-bit and float PCM; other encodings pass through untouched.
- Crossfade is time-based on the main thread (50 ms ramp steps), so it is smooth but not sample-accurate.
- Notification artwork uses the legacy album-art URI, which some devices don't serve.
