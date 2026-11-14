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
4. Export as usual. Overlays are included in videos and screenshots, including with **No GUI** enabled.

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

Milestone 1 of the [design](docs/DESIGN.md): images and text in world and screen space with keyframed transform,
appearance and content. Viewport gizmos, entity attachment, shapes, custom fonts, GIF/video, groups, blend modes,
effects and presets are planned for later milestones.

## Building

```sh
./gradlew build               # needs JDK 25
./gradlew runClient           # dev client with Flashback
./gradlew runClientGameTest   # end-to-end test: records a replay, adds overlays, checks screenshots and an export
                              # (on a headless machine: xvfb-run -a ./gradlew runClientGameTest)
```

Flashback is "all rights reserved", so it is a compile-only dependency fetched from Modrinth's Maven and is never
bundled into the Billboards jar.

## License

CC0-1.0
