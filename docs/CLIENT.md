# BoilerClient for Minecraft Java 26.3

BoilerClient is an optional Fabric companion mod. Boiler keeps the source and its shared app state on the server. Modded viewers receive full-color frames over a separate WebSocket connection and render a depth-tested texture in the world. Other viewers continue receiving MapEngine maps. A lost or failed media connection returns the affected viewer to maps on the next display update (normally within one second).

The standalone mod project is `boilerclient` (Java 25, Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API 0.165.0+26.3). The plugin keeps its existing Paper API baseline and MapEngine/MapMediaExt dependencies. This streams the display framebuffer directly to the companion mod.

## Setup

1. Install the built Boiler plugin and its existing dependencies on the server. Install `boilerclient-1.0.0.jar` and Fabric API on the Minecraft **26.3** client. The mod bundles FFmpeg/JavaCPP desktop native libraries for Windows x64, Linux x64, macOS x64 and macOS ARM64; no separate FFmpeg installation is needed on the client.
2. Enable the new `boiler.stream` section in `plugins/Boiler/config.yml`, then fully restart the server. The legacy `boiler.client` section does not enable this feature.
3. For a same-computer test, use:

```yaml
boiler:
  stream:
    enabled: true
    bind: "127.0.0.1"
    port: 25576
    public_url: "ws://127.0.0.1:25576/boiler/v1"
    codec: "auto"
    fps: 30
    resolution_scale: 1
```

The shipped defaults keep streaming disabled and bind to localhost. For a remote deployment, forward `/boiler/v1` through a TLS reverse proxy to the localhost listener, enable WebSocket upgrades and set `public_url: "wss://your-host/boiler/v1"`. Minecraft's port alone does not carry the video. `{server}` in the URL is replaced by the actual Minecraft connection host, including IPv6 brackets. Set an explicit public URL when using a Minecraft proxy or separate media host. Restart after changing listener, endpoint or default encoder settings.

## Controls and display settings

Aim at the screen and use left/right click, scroll, arrow keys, Enter, Backspace, Escape or Home. Mouse input uses the 26.3 SDL constants and maps physical left/right buttons to primary/secondary browser clicks, independently of rebound Minecraft attack/use controls. F6 opens a text field; Enter sends its text to the current source. These ordinary keys remain app inputs; the existing on-screen remote supplies TV Home/Back actions. Inputs are only captured when aiming at an unobstructed display within six blocks. The server independently verifies subscription, generation, world, reach, line of sight and hit coordinates, and retains source permission checks.

Left clicks consumed by a mod display also suppress Minecraft's initial attack and held-button mining paths, clear queued attack input and cancel existing mining. A mouse hold captured by the display stays captured until release, even if the display disappears. The 26.3 game test verifies that a real backing block remains intact on both client and integrated server, and that normal block breaking works again after release.

```text
/boiler display settings <id> clientCodec auto
/boiler display settings <id> clientCodec rgb
/boiler display settings <id> clientCodec h264
/boiler display settings <id> clientScale 2
```

`rgb` sends lossless 24-bit color, using compressed changed rectangles. `h264` sends low-delay video using software encoding without B-frames. The encoder uses libx264 when available, otherwise the OpenH264 encoder supplied by the existing FFmpeg runtime. If video encoding is unavailable, the display falls back to lossless RGB. `auto` switches to H.264 after sustained large image changes and back to RGB after calm frames. Force `rgb` for exact colored text and graphics; H.264 uses lossy YUV 4:2:0 with explicit BT.709 conversion.

Clients advertise their available decoders during the handshake. If a subscribed client cannot decode H.264, that display uses shared RGB output for its mod viewers until the client leaves.

`clientScale` accepts 1–4 and is capped at 2048 pixels on either axis. A 4×3-block display normally has 512×384 map pixels; scale 2 gives the mod a 1024×768 texture while keeping its world size and input coordinates. Java2D sources draw into the larger target using their existing logical coordinates. Timed media sources keep a larger source image while a mod output is active. Raster sources that themselves only supply a small image remain limited by that source's resolution; increasing scale cannot reconstruct missing detail. Vanilla viewers receive a downsampled map frame.

The source's render period also limits the achievable frame rate. The `fps` setting is a maximum; encoder load, network delay, decode speed and client frame rate can reduce it. Source playback/audio still uses the existing lifecycle and voice-chat path. There is no new synchronized audio transport in this version.

## Compatibility and resource limits

- Normal wall, floor and ceiling displays use a single texture. Individual item-frame rotation, custom entity rotation and a differing visual direction retain the MapEngine path. Displays larger than 2048 pixels per axis also retain maps.
  Explicitly saved angles that match the display's facing still use the mod output (for example SOUTH with yaw 0 / pitch 0). Merely having a `rotation` entry does not select the map fallback.
- Sources implementing `draw(Graphics2D, Rectangle)` work with the new output, including SmartTV, media player, DJ controller, remote and clone composition. A third-party source implementing only `draw(IDrawingSpace)` retains maps. Composite providers must expose a genuine color drawing implementation for their children.
- Up to 32 displays and 16 million aggregate texture pixels are allowed per media connection. Additional displays retain maps.
- Each display has one encoder shared by its subscribers. There is at most one snapshot/encode operation per display, a bounded dedicated encoder executor, one asynchronous socket write per player and one unacknowledged frame per subscribed display. Stale frames are dropped; the next transmission recovers from a keyframe. An unacknowledged frame times out after ten seconds.
- Short-lived, single-use bearer tickets are issued over the authenticated Minecraft session. A ticket expires after 15 seconds. Disconnect removes tickets and subscriptions. World/distance changes remove displays. Replaced displays have new generations so old frames and inputs are discarded.
- The plugin does not open client-selected URLs or let the client subscribe to arbitrary display IDs. Listener failures leave maps available. No video or decoding work runs on the Minecraft server tick thread; source raster drawing uses the existing render executor.

## Development and verification

Build the plugin with Java 21: `gradlew.bat :plugin:test :plugin:shadowJar`. Build the client with Java 25: `gradlew.bat build`. Run its in-game rendering test with `gradlew.bat runClientGameTest` from the client project. This creates an isolated test world, uploads a full-color gradient through the real 26.3 world pipeline, verifies the display hit test and opens the text screen. Screenshots are written below `build/run/clientGameTest/screenshots`.

`StreamProtocol.java` and `H264Codec.java` are intentionally identical in both projects. Run `scripts/sync-client-protocol.ps1 -ClientRoot <path-to-boilerclient>` after changing them. Keep the protocol version synchronized across releases.

Tests cover RGB full/delta frames and malformed inputs, H.264 color decoding and independent recovery frames, automatic codec selection, raster ownership under asynchronous encoding, actual local WebSocket authentication/transfer/backpressure, mixed mod/Vanilla receivers and fallback to maps. The in-game fixture exercises rendering separately from the Paper integration; it does not replace a live multiplayer acceptance test with both clients.

Verification on 2026-10-23: the 22 targeted plugin tests, four client unit tests, both JAR builds and the Minecraft 26.3 client game test passed. The full plugin suite also ran: three SmartTV tests failed because their fixtures register/expect `yt-tv` while the existing SmartTV home filter uses `youtube-tv`. That separate naming mismatch was left unchanged. The game-test screenshots were visually checked. A live mixed-client session on Paper remains an integration acceptance check.
