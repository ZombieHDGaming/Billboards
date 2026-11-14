# Billboards

A [Fabric](https://fabricmc.net/) add-on for [Flashback](https://modrinth.com/mod/flashback) that lets you place
images and text inside a replay — in the world or pinned to the screen — and animate them on Flashback's own
timeline. Overlays render straight into Flashback's exports, so there is no video editing afterwards.

**Requires:** Minecraft 26.3, Fabric Loader, Fabric API, Flashback 0.43.x.

## Using it

1. Open a replay in Flashback and click **Billboards** in the menu bar.
2. **Add image...** or **Add text**, either *In the world* (placed 3 blocks in front of the camera, facing it) or
   *On screen* (centred in the frame).
3. Select an overlay to edit it in the inspector:
   - **Settings** (not animated): facing, hidden-behind-blocks, pivot, layer, size, font size, alignment, shadow,
     background.
   - **Transform**, **Appearance** and **Content** are animatable channels. Click **Animate** to add a keyframe at the
     timeline cursor; a track named `<overlay> · <channel>` appears on Flashback's timeline. From then on, editing a
     value adds or updates a keyframe at the cursor. Keyframes work like any other Flashback keyframe: drag them,
     change their interpolation, copy/paste them, undo/redo.
4. **In the viewport**, click an overlay to select it and drag its gizmo. **W** / **E** / **R** (or the toolbar in the
   Billboards window) switch between move, rotate and scale. Options: *Local axes* (move and rotate along the overlay's
   own axes), *Snap* (0.25 blocks / 1% of the frame, 15°, 0.1 scale) and *Auto-key* (edits on a channel that isn't
   animated yet start animating it instead of changing its base value). A drag on an animated channel writes one
   keyframe at the cursor when you let go, so it's one undo step.
5. **Attach to an entity** (world overlays, under *Settings*): pick a nearby player or mob and the overlay follows it.
   Its position becomes an offset from an anchor (feet, centre, eyes or above the head), optionally turning with the
   entity. Attach a camera-facing text overlay with the *Above head* anchor for a custom name tag.
6. Export as usual. Overlays are included in videos and screenshots, including with **No GUI** enabled.

| Channel | Values |
|---|---|
| Transform | position (world: block coordinates, screen: 0–1 of the frame), rotation (pitch/yaw/roll in degrees, so spins past 360° work), scale |
| Appearance | visible, opacity, tint / text colour |
| Content | text, reveal (typewriter effect), crop |

### Where data is stored

Keyframes live in Flashback's editor state. Everything else (overlay settings and copied-in images) lives in
`<Flashback data folder>/billboards/projects/<replay id>/`. Picked images are copied there, so moving or deleting the
original file doesn't break the replay.

> **Note:** if you open a replay that has Billboards keyframes without Billboards installed, Flashback cannot read that
> replay's editor state and will start it with an empty timeline.

## Status

Milestones 1 and 2 of the [design](docs/DESIGN.md): images and text in world and screen space with keyframed
transform, appearance and content; viewport selection, move/rotate/scale gizmos and entity attachment. Shapes,
custom fonts, GIF/video, groups, blend modes, effects and presets are planned for later milestones.

## Building

```sh
./gradlew build               # needs JDK 25
./gradlew runClient           # dev client with Flashback
```

### Tests

```sh
./gradlew runClientGameTest   # records a replay with Flashback, opens it, adds overlays with keyframes and checks
                              # interpolation, screenshots at several ticks, saving/loading of keyframes, viewport
                              # picking, gizmo maths and entity attachment; with xdotool installed it also clicks and
                              # drags gizmos with the real mouse and saves full-window screenshots
./gradlew runExportCheck      # then: opens that replay and runs two real Flashback exports (a PNG sequence like a
                              # video export, and a "No GUI" screenshot) and checks the overlays in the output
```

Exports are checked separately because they can't run inside Fabric's client gametest (it ticks the server in
lock-step with the client, while an export blocks the client until the server catches up).

On a headless Linux machine, run them under Xvfb with GLX enabled. Xvfb has no sRGB-capable visuals, so
`BILLBOARDS_NO_SRGB=1` tells the test mod to stop Minecraft from asking for one:

```sh
BILLBOARDS_NO_SRGB=1 xvfb-run -a -s "-screen 0 1280x720x24 +extension GLX +render" ./gradlew runClientGameTest
```

Flashback is "all rights reserved", so it is a compile-only dependency fetched from Modrinth's Maven and is never
bundled into the Billboards jar.

## License

CC0-1.0
