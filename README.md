# Boiler
Extension Plugin for MapEngine that allows you to show different types of content on a map display

Optional **BoilerClient for Minecraft 26.3** adds full-color world textures, lossless RGB updates and H.264 video while keeping maps for Vanilla viewers. See [client setup and architecture](docs/CLIENT.md).

Download:
https://modrinth.com/plugin/boiler

All docs have been moved to the wiki tab
https://github.com/mrmrmystery/Boiler/wiki

## Performance and verification

See [the performance report](docs/PERFORMANCE.md) for implementation details, the optional live-buffer mode, Java 21 build instructions, regression tests, measured raster allocations, and remaining server integration checks.

## Remote control and SmartTV

Activate the TV launcher with `/boiler display source <tvDisplayId> smarttv`, then put its clickable remote on another display with `/boiler display source <remoteDisplayId> remote <tvDisplayId>`. Home returns to the full TV home screen; Back sends Escape to the active app. Ordinary keyboard Shift, Escape and Home remain app inputs. The former remote item and movement lock have been removed.

SmartTV shows only the registered `yt-tv`, `browser`, `mediaplayer` and `djcontroller` sources plus Settings. Other sources remain available through commands. Required parameters are requested in private Minecraft dialogs and remembered. See [the SmartTV guide](docs/SMARTTV.md) for permissions, parameter editing, lifecycle behavior, and verification.

Displays of at least 3×2 or 2×3 maps use a webOS-inspired home screen with a sidebar, category shortcuts, an app dock, and recent sources. The Settings app edits all existing display options, including dithering, buffering, render period, distances, orientation, volume and mute; it requires `boiler.command.settings`. Smaller displays retain a compact layout.

SmartTV uses Minecraft's original server-provided bitmap font at whole-number scales only (1×, 2×, 3×, etc.). Text and icons are rendered without antialiasing, directly at map resolution; font measurements and clipping use the actual pixel glyphs.

## Local media player

Put images, videos and audio in `plugins/Boiler/media`, including subfolders. Open **Mediaplayer** in SmartTV, or use `/boiler display source <displayId> mediaplayer`. Browse with arrows/OK or clicks; Back returns from playback to the file list. Existing media controls and the SmartTV settings overlay remain available. See [the media player guide](docs/MEDIAPLAYER.md) for file types, permissions and operation. Set `boiler.mediaplayer.backend` to `ffmpeg` (default), `gstreamer` or `vlc`. Direct URLs use `/boiler display source <displayId> mediaplayer <url>`; YouTube and Twitch use the same player after resolving their URLs. There is no automatic backend fallback. The separate FFmpeg/VLC/GStreamer/converter sources have been removed without migration.

## DJ controller

Open `/boiler display source <displayId> djcontroller` on two side-by-side maps, or launch **DJ Controller** from SmartTV. LOAD opens a file browser overlay on the controller to select local audio while both decks keep playing. Aim at a knob, fader or turntable and scroll to adjust EQ, volume, crossfade, pitch-preserving tempo or playback position. Both decks support manual loops, frequency-colored scrolling waveforms and one-shot SYNC using estimated BPM and beat positions. See [the DJ controller guide](docs/DJCONTROLLER.md) for controls, preparation and verification.

## Bundled CEF browser source

When launched from SmartTV, the registered `browser` source opens Google directly without requesting a URL.

The `browser` source runs Chromium off-screen on the server and sends its framebuffer to the map display. Create one with the normal source command and a URL, for example `browser https://example.com`. Click, cursor, scroll, and text events are forwarded to the page. Browser PCM is routed through Boiler's existing voice-chat speaker path.

The release JAR contains CEF 128.4.12, its Chromium runtime/resources, Boiler's JNI bridge, and Linux support libraries. On first use the matching platform bundle is checksum-verified and extracted under `plugins/Boiler/cef/128.4.12/<platform>`; the server does not download or install anything. Windows x64 and Linux x64 are supported.

CEF is shared by all browser sources and shuts down with the plugin. A full server restart is required after unloading/reloading Boiler because native libraries cannot be safely unloaded from the JVM.

Native sources and their CMake build are in `native/cef`. The bundled CEF and Linux-library license notices are included with the runtime resources. Linux requires a glibc-based x86_64 distribution and Java 21, but no optional desktop, audio, or browser packages.
