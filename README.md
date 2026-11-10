# Cadence

Apple Music-inspired local music player for Android with ReplayGain, crossfade and gapless playback.

## Open it
1. Android Studio (Ladybug or newer) -> Open -> select this folder. Let Gradle sync.
2. Run on a device or emulator with API 29+ and some music files on it.

## How it works
| Feature | Where |
|---|---|
| Gapless | `CadencePlayer` loads the whole queue into one ExoPlayer when crossfade is off |
| Crossfade | `CadencePlayer` runs two ExoPlayer decks, preloads the next track, equal-power volume ramp |
| ReplayGain | `data/LibraryScanner.kt` reads tags (ID3, Vorbis/FLAC/Opus, MP4) into Room; `playback/ReplayGain.kt` applies gain in the audio sink |
| Lock screen / notification | `PlaybackService` wraps the player in a Media3 `MediaSession` |
| Folders | `ui/FolderBrowser.kt` browses MediaStore `RELATIVE_PATH`; Play/Shuffle cover the folder and its subfolders |
| Queue | `ui/QueueList.kt` (queue button on Now Playing); songs have Play next / Add to queue in their menu |
| Shuffle | `playback/SecureShuffle.kt` (Fisher-Yates + SecureRandom), applied by `CadencePlayer.setShuffle` |
| Playlists | `data/UserData.kt` (own Room database), `ui/PlaylistScreens.kt`; "Save as playlist" in the folder browser |
| Multi-select | `ui/Selection.kt`; long-press a song or use its menu, then add to playlist / queue / delete in bulk |
| Tag editing | `data/TagEditor.kt` (jaudiotagger, via JitPack); `MainActivity` handles the write-permission prompt |
| Delete from storage | `MainActivity.deleteTracks` (system delete request on Android 11+, per-file prompt on Android 10) |
| Home + listening stats | `CadencePlayer` counts real playing time, `ui/HomeScreen.kt` shows today / 7 days / 30 days / lifetime |
| Now Playing gradient | `rememberArtGradient` in `ui/Components.kt` (AndroidX Palette) |
| Notification / watch / Bluetooth controls | `playback/PlaybackService.kt` (MediaSession), cover art via `playback/ThumbnailBitmapLoader.kt` |
| UI | Jetpack Compose, `ui/` |

## Known limitations
- Never compiled or run: expect to fix a few small API mismatches on first build.
- Not built yet: artists tab, reordering queue/playlist items, equalizer, sleep timer, lyrics, album art editing, widgets.
- The library index is a cache and may be rebuilt on upgrade. Playlists and listening history are in a separate database and are kept.
- Tag editing needs the JitPack repository at build time (already added to settings.gradle.kts).
- ReplayGain is only applied to 16-bit and float PCM; other encodings pass through untouched.
- Crossfade is time-based on the main thread (50 ms ramp steps), so it is smooth but not sample-accurate.
- Notification artwork uses the legacy album-art URI, which some devices don't serve.
