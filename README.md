# Billboards

A [Fabric](https://fabricmc.net/) add-on for [Flashback](https://modrinth.com/mod/flashback) that lets you place
images, text, shapes and videos inside a replay's world and animate them on Flashback's own timeline. Text works like
vanilla text displays. Overlays render straight into Flashback's exports, so there is no video editing afterwards.

**Requires:** Minecraft 26.3, Fabric Loader, Fabric API, Flashback 0.43.x.

## Using it

1. Open a replay in Flashback and click **Billboards** in the menu bar.
2. **Add image...**, **Add text**, **Add shape**, ... (placed 3 blocks in front of the camera, facing it).
3. Select an overlay to edit it in the inspector:
   - **Settings** (not animated): billboard mode, see through blocks, lighting, pivot, layer, size, line width,
     alignment, shadow, background.
   - **Transform**, **Appearance** and **Content** are animatable channels. Click **Animate** to add a keyframe at the
     timeline cursor; a track named `<overlay> · <channel>` appears on Flashback's timeline. From then on, editing a
     value adds or updates a keyframe at the cursor. Keyframes work like any other Flashback keyframe: drag them,
     change their interpolation, copy/paste them, undo/redo.
4. **In the viewport**, click an overlay to select it and drag its gizmo. **W** / **E** / **R** (or the toolbar in the
   Billboards window) switch between move, rotate and scale. Options: *Local axes* (move and rotate along the overlay's
   own axes), *Snap* (0.25 blocks, 15°, 0.1 scale) and *Auto-key* (edits on a channel that isn't
   animated yet start animating it instead of changing its base value). A drag on an animated channel writes one
   keyframe at the cursor when you let go, so it's one undo step.
5. **Attach to an entity** (under *Settings*): pick a nearby player or mob and the overlay follows it.
   Its position becomes an offset from an anchor (feet, centre, eyes or above the head), optionally turning with the
   entity. Attach a text overlay with the *Center* billboard and the *Above head* anchor for a custom name tag.
6. **Shapes** (*Add shape*): rectangles, rounded rectangles, ellipses, lines and arrows, with a solid or gradient
   fill and an optional outline. Tinted and faded by the Appearance channel like everything else.
7. **Fonts**: text uses Minecraft's font (including resource-pack fonts) or, via *Font: Choose...*, any TTF/OTF file,
   which is copied into the project like images.
8. **Groups** (*Add group*): a group's position, rotation, scale, opacity and tint apply to everything inside it, so a
   lower third or a sign made of several pieces animates as one. Select a group before adding something to put it
   inside, or use *Move into group* / *Move out of group* / *Ungroup* in the list's right-click menu (elements stay where
   they are). Clicking a group's contents in the viewport selects that piece; select the group itself in the
   list.
9. **Animated images and videos**: animated GIFs and PNGs added with *Add image...* play automatically; *Add video...*
   takes MP4, MOV, MKV, WebM and more. They play in sync with the replay (following its speed), starting at a chosen
   tick, at a chosen speed, looping or holding the last frame. Exports get exactly the right frame for every exported
   frame. Video sound isn't included.
10. **Look** (inspector): blend mode (normal, additive, multiply, screen), drop shadow, outline, glow, and blur for
    images and videos.
11. **Presets** (*Presets* menu): right-click an overlay or group in the list and *Save as preset...* to reuse it,
    with its animation, in any replay. Presets are inserted at the timeline cursor, in front of the camera.
12. **Sharing** (*Share* menu): Flashback keeps its editor data (keyframes) outside the replay file, and so does
    Billboards. To give someone a replay with its overlays, *Export pack...* and send the pack with the replay; they
    open the replay and *Import pack...*.
13. **Missing files** are listed at the top of the window and shown as a magenta checkerboard; *Relink...* points them
    at a file again.
14. Export as usual. Overlays are included in videos and screenshots, including with **No GUI** enabled.

### Text

Text overlays work like vanilla [text displays](https://minecraft.wiki/w/Display#Text_displays): 40 font pixels per
block, lines 10 pixels apart, a 25% black background one pixel around the text (change or remove it under
*Background*), lines wrapping at the *line width* (200 pixels by default), left / centre / right alignment, an optional
shadow, lighting from the world, and fog with distance. Make text bigger with the Transform channel's scale.

Text can be styled three ways (*Text format* picks one, or *Auto* tells them apart):

- **[MiniMessage](https://docs.advntr.dev/minimessage/format.html)** tags: `<gradient:#ff8800:#ff0044>Hello</gradient>`,
  `<rainbow>`, `<bold>`, `<italic>`, `<#33ccff>`, `<shadow:#000000aa>`, `<font:...>` and more.
- **Formatting codes** with `&` or `§`: `&6&lGold and bold`, `&r` to reset.
- A **JSON text component**, as in commands: `{"text":"Hello","color":"gold","bold":true}`.

Colours from the styling replace the text colour; the Appearance opacity still fades it. Choose *Formatting codes*
to show something like `<b>` literally.

| Setting | Like a text display's... |
|---|---|
| Billboard: Fixed / Vertical / Horizontal / Center | `billboard` (fixed, vertical, horizontal, center) |
| See through blocks | `see_through` |
| Lighting: World light / Full bright / Custom levels | normal lighting / `brightness` override |
| Fog | lit overlays that aren't see-through fade into the world's fog, like text displays |
| Line width, Align, Shadow, Background | `line_width`, `alignment`, `shadow`, `background` |
| Appearance opacity | `text_opacity` (but fading smoothly all the way to 0) |

Lighting (and with it fog) works for images, videos and shapes too; they default to *Full bright*, which skips both,
so their colours show as they are.

| Channel | Values |
|---|---|
| Transform | position (block coordinates), rotation (pitch/yaw/roll in degrees, so spins past 360° work), scale |
| Appearance | visible, opacity, tint / text colour |
| Content | text, reveal (typewriter effect), crop |

### Where data is stored

Keyframes live in Flashback's editor state. Everything else (overlay settings and copied-in images) lives in
`<Flashback data folder>/billboards/projects/<replay id>/`. Picked images are copied there, so moving or deleting the
original file doesn't break the replay.

> **Note:** if you open a replay that has Billboards keyframes without Billboards installed, Flashback cannot read that
> replay's editor state and will start it with an empty timeline.

## Status

All five milestones of the [design](docs/DESIGN.md) are implemented: images, text, shapes, groups, animated
GIFs/PNGs and videos in the world with keyframed transform, appearance and content; text like vanilla text displays; custom fonts;
viewport selection, move/rotate/scale gizmos and entity attachment; blend modes and effects; presets, packs and
relinking; translatable UI.

## Translating

UI text is in `src/client/resources/assets/billboards/lang/en_us.json`. Copy it to your language's file (e.g.
`de_de.json`) and translate the values; keep `%s` placeholders. Developers: UI text is written in English in the code
as `Lang.tr("...")`; after changing it, run `python3 scripts/update_lang.py` to regenerate `en_us.json`.

## Building

```sh
./gradlew build               # needs JDK 25
./gradlew runClient           # dev client with Flashback
```

### Tests

```sh
./gradlew runClientGameTest   # records a replay with Flashback, opens it, adds overlays with keyframes and checks
                              # interpolation, screenshots at several ticks, saving/loading of keyframes, viewport
                              # picking, gizmo maths, entity attachment, text-display layout, wrapping, formatting
                              # codes, JSON text, billboard modes and lighting, a lower third (group + shape + TTF text)
                              # sliding in, a GIF, APNG and video playing in sync, blend modes and effects against exact
                              # colours, presets and packs, and the language file; with xdotool installed it also
                              # clicks and drags gizmos with the real mouse and saves full-window screenshots
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

Billboards bundles [Adventure](https://github.com/KyoriPowered/adventure)'s MiniMessage and JSON serializer (MIT) inside
its jar. Flashback is "all rights reserved", so it is a compile-only dependency fetched from Modrinth's Maven and is never
bundled into the Billboards jar.

## License

CC0-1.0
