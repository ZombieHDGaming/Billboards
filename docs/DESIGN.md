# Billboards — Design Document

**Billboards** is a Fabric add-on for [Flashback](https://modrinth.com/mod/flashback). It lets you place images,
text, shapes, GIFs and videos in a replay, in the world or on screen, and animate them on Flashback's own timeline.
Overlays render straight into Flashback's export, so they need no video editing afterwards.

| | |
|---|---|
| Mod name / id | Billboards / `billboards` |
| Package | `me.zhdg.billboards` |
| Target | Minecraft 26.3, Fabric, Flashback 0.43.x (built against 0.43.6) |
| Java | 25 |
| Status | Approved. Milestone 1 implemented. |

---

## 1. Decisions captured

| Topic | Decision |
|---|---|
| Space | **World-space** (placed at XYZ, part of the scene) **and screen-space** (pinned to the frame, like a title layer) |
| Animation | **Flashback's own keyframe timeline**: overlay tracks sit next to camera/FOV tracks |
| Element types | Images (PNG/JPG), Text, Animated media (GIF/APNG + video), Shapes / color fills |
| Keyframeable | Position, rotation, opacity, **scale/size, color/tint, visibility, text content, crop** |
| World facing | Per element: **Fixed**, **Face camera**, **Y-axis only** |
| Occlusion | Per element: **depth-tested** (default) or **always on top** |
| Easing | **Reuse Flashback's interpolation types** (Smooth, Linear, Ease In/Out/In-Out, Hold, Hermite) |
| Editing | **ImGuizmo viewport gizmos + an ImGui properties panel** |
| Assets | **File picker; files are copied in** so the project is self-contained |
| Video | **Decode with the FFmpeg/JavaCV that Flashback already ships** |
| Text | **Minecraft fonts (incl. resource-pack fonts) + custom TTF/OTF** |
| Attachment | World elements can **attach to an entity** with a keyframeable offset |
| Extras | **Blend modes, groups/layers, presets/templates, simple effects** (shadow, outline, glow, blur) |
| Versions | **26.3 only** |

---

## 2. Constraints found in Flashback's source

These come from reading Flashback 0.43.6 (`Moulberry/Flashback`), and they drive the architecture.

1. **License: "All rights reserved, do not redistribute."** Billboards compiles against Flashback
   (`modCompileOnly`) and declares `"depends": { "flashback": ">=0.43.6 <0.44" }`. Users install Flashback
   themselves; Billboards never bundles or shades it. We only *reference* Flashback's bundled ImGui and JavaCV and
   never ship copies.
2. **Keyframe types are pluggable, but serialization is not.** `KeyframeRegistry.register(KeyframeType)` is public,
   so custom types appear in the "add track" UI. However, `Keyframe.TypeAdapter` is a hardcoded `switch` on the
   `"type"` string and throws on unknown types. **A mixin is required** on `Keyframe.TypeAdapter` to
   (de)serialize `billboards:*` keyframes, or saving and loading the editor state breaks.
3. **Flashback's apply loop doesn't fit stateless overlays.** `EditorState.applyKeyframes` only applies tracks a
   handler whitelists, applies one track per change class, and `KeyframeTrack.createKeyframeChange` returns nothing
   after a track's last keyframe (Flashback's own targets keep their last value). So Billboards' keyframe types
   report that **no handler supports them**, and Billboards **evaluates its own tracks** each frame
   (`KeyframeEvaluator`). It clamps the tick to the track's first..last keyframe and calls the same
   `createKeyframeChange`, so every interpolation type and the real-time (tickrate) mapping behave exactly like
   Flashback's tracks. No handler mixin is needed.
4. **Time source.** In the editor this is `ReplayServer.getPartialReplayTick()` with the live editor state. While
   exporting it is the export job's exact tick and its copy of the editor state, which keeps exports
   frame-accurate. "Export screenshot" copies the state *without* keyframes, so in that case the live state is used.
5. **Locks.** Scene access goes through the editor state's `StampedLock`. Billboards only uses `tryReadLock` and
   `tryWriteLock` (via an accessor mixin), so it can never deadlock against the timeline holding the lock on the
   same thread.
6. **Tracks carry no custom metadata.** `KeyframeTrack` holds only `keyframeType`, `keyframesByTick`, `enabled`,
   `customName` and `customColour`. To tie a track to an element, **each keyframe stores its `elementId`**, and
   tracks are created from the Billboards panel or from the type's `createPopup()` (which asks which element).
7. **Editor state lives outside the replay file**, in `<flashback data>/editor_states/<replayUuid>.json`.
   Billboards follows the same pattern (see §4).
8. **Rendering:** Flashback hooks world rendering via a `LevelRenderer` mixin (`WorldRenderHook`), uses 26.3's
   new render API (`com.mojang.renderpearl`, `RenderPipelines`, `RenderType`), and hides the GUI during export
   when "No GUI" is set (`MixinGameRenderer.render_noGui`). Screen-space overlays must therefore **not** draw in the
   vanilla GUI pass, or "No GUI" exports would drop them.
9. **Bundled libraries we can use:** `imgui.moulberry90.*` (ImGui 1.90 **with ImGuizmo**,
   `imgui.moulberry90.extension.imguizmo`), and `org.bytedeco.javacv` / FFmpeg 8.1 (unrelocated). Flashback's
   own `CatmullRom` and `Hermite` spline helpers provide interpolation that matches its other tracks.

> Because of items 2, 3 and 5, Billboards depends on Flashback internals (mixins: keyframe JSON adapter, editor-state
> accessor, menu bar entry, window rendering). Each Flashback update needs a quick
> compatibility check, which is why the dependency range is pinned to `<0.44`. Long term, it is worth proposing a
> small public extension API upstream to Moulberry (registering keyframe (de)serializers and a generic handler
> hook), which would remove most of the mixins.

---

## 3. Data model

```
BillboardProject (one per replay)
├── version: int
├── assets: Map<assetId, AssetRef>          // copied files, by content hash
└── root: GroupElement
    └── children: List<Element>
        ├── GroupElement      (children, blend/opacity apply to the whole group)
        ├── ImageElement      (assetId, crop rect, filtering)
        ├── MediaElement      (assetId, kind GIF|APNG|VIDEO, startTick, speed, loop, mute*)
        ├── TextElement       (text, font=Minecraft|ResourcePack(id)|Custom(assetId), size, align,
        │                      color, outline, shadow, maxWidth, lineHeight)
        └── ShapeElement      (RECT|ROUNDED_RECT|ELLIPSE|LINE|ARROW, fill solid|gradient, stroke)
```

Common fields on every `Element`:

| Field | Notes |
|---|---|
| `id: UUID`, `name`, `locked`, `hiddenInEditor` | |
| `space: WORLD \| SCREEN` | Fixed at creation (changing it converts coordinates) |
| `facing: FIXED \| FACE_CAMERA \| Y_AXIS` | World only |
| `depthTest: boolean` | World only, default `true` |
| `attach: null \| EntityAttach{entityUuid, anchor: FEET\|EYES\|HEAD_TOP, inheritYaw}` | World only |
| `anchor/pivot: Vec2` | 0–1 within the element, default centre |
| `layer: int` | Draw order within its parent (screen-space and always-on-top) |
| `blend: NORMAL \| ADDITIVE \| MULTIPLY \| SCREEN` | |
| `effects: List<Effect>` | DropShadow, Outline, Glow, Blur (milestone 5) |
| `base: PropertySet` | Values used when no keyframe applies |

**`PropertySet`** holds the keyframeable values, split into three **channels** so they can be animated
independently:

| Channel | Properties | Interpolation |
|---|---|---|
| **Transform** | position (`Vec3` world / `Vec2` normalized screen), rotation (pitch/yaw/roll degrees), scale (`Vec2`) | Flashback types, per component. Euler angles (not quaternions) so 720° spins and multi-turn animations work. Size is a static setting; animate scale instead |
| **Appearance** | opacity, tint/color (RGBA), visible (bool) | opacity & color interpolate (color in linear RGB); `visible` is always step |
| **Content** | text string, crop rect, media time offset | text: step (with an optional "typewriter reveal" count that interpolates); crop interpolates |

**Screen-space coordinates are normalized** (0–1 of the frame plus an aspect-correct size unit). An overlay
therefore lands in the same place at any export resolution, aspect ratio or SSAA setting.

---

## 4. Persistence & assets

```
<flashback data>/billboards/
├── projects/<replayUuid>/
│   ├── project.json          // BillboardProject (static element data)
│   ├── project.json.old      // backup, same rotation scheme as Flashback's editor state
│   └── assets/<sha256>.<ext> // copied-in files, deduplicated by hash
├── presets/<name>.bbpreset   // zip: element subtree JSON + its assets
└── fonts/                    // cache of rasterized glyph atlases (safe to delete)
```

- **Keyframes** are stored by Flashback in its own editor-state JSON (enabled by the TypeAdapter mixin), so undo/redo,
  scenes and copy/paste of keyframes all work natively.
- **Static element data** lives in `project.json`, saved whenever Flashback saves (`EditorStateManager.saveIfNeeded`
  is hooked), with its own undo stack mirrored into Flashback's history where practical.
- **Sharing:** a "Pack for sharing" action writes `billboards.zip` (project + assets). Flashback's editor state is
  not inside the `.zip` replay either, so this matches how Flashback already behaves.
- Missing assets render as a magenta placeholder with the original filename, and the panel offers "Relink…".

---

## 5. Timeline integration

Billboards registers **three keyframe types**, one per channel:

| Type id | Keyframe class | Change class |
|---|---|---|
| `billboards:transform` | `TransformKeyframe(elementId, position, rotation, scale, size)` | `TransformChange` |
| `billboards:appearance` | `AppearanceKeyframe(elementId, opacity, color, visible)` | `AppearanceChange` |
| `billboards:content` | `ContentKeyframe(elementId, text, reveal, crop, mediaOffset)` | `ContentChange` |

- An element gets **one track per channel it animates**, created on demand. The track's `customName` is set to
  `"<element name> · Transform"` and its `customColour` to the element's colour tag. Tracks you never animate are
  never created, which keeps the timeline uncluttered.
- Each keyframe implements `createChange`, `createSmoothInterpolatedChange` (Flashback's `CatmullRom`),
  `createHermiteInterpolatedChange` (Flashback's `Hermite`) and `renderEditKeyframe` (ImGui fields in Flashback's
  keyframe edit popup). All of Flashback's interpolation types and its real-time/tickrate mapping then work
  automatically.
- `KeyframeEvaluator` produces the animated values for the current frame (see §2.3). A channel with a track uses the
  track's value; a channel without one uses the element's `base` values.
- **Before the first keyframe** a channel holds its first keyframe's values, and **after the last** it holds the
  last one's. Use `visible` or opacity to make an element appear partway through the replay.
- **Auto-key in the inspector:** on a channel that isn't animated, edits change the base value. Once a channel is
  animated, edits add or update a keyframe at the cursor.
- Disabling a track in Flashback disables that channel's animation for that element.
- **Inspector keyframe buttons:** every animatable property has a ◆ button. Clicking it writes a keyframe at the
  timeline cursor into the element's channel track via `EditorScene.setKeyframe`, so it is undoable.

---

## 6. Rendering

### World-space

- Hooked at the same point as Flashback's `WorldRenderHook` (a `LevelRenderer` mixin), after translucent geometry.
  Elements are drawn **in the export too** (unlike Flashback's editor-only gizmos).
- Each element is a textured quad (or a tessellated shape) in the camera-relative pose:
  `entity attach → position → facing → rotation → scale → pivot`.
- `depthTest=false` uses a pipeline with depth testing disabled, drawn last in layer order.
- Translucent elements are sorted back-to-front by distance to the camera.
- **Entity attach** reads the entity's interpolated position for the current partial tick (as Flashback's
  `AccurateEntityPositionHandler` does), so attached elements don't jitter in exports.

### Screen-space

- Rendered in **our own pass at the end of `GameRenderer.renderLevel`** (after the world, hand and screen
  effects, before the HUD), so they appear in "No GUI" exports, in transparent-background exports, and in the editor
  viewport, but never over Flashback's ImGui panels.
- They use their own pixel-space orthographic projection, built from normalized coordinates (§3) and the main
  render target's size, so camera bobbing, hurt-cam, shake and roll never move titles.

### Shader

Both passes draw `POSITION_TEX_COLOR` quads with a small Billboards shader (`texture × vertex colour`, with a
grayscale variant for TTF glyph atlases). Vanilla's text shader discards pixels below 10% alpha, which would make text
fades pop. Depth-tested overlays test against the world but don't write depth, so overlapping overlays never z-fight.

### Content renderers

| Element | Approach |
|---|---|
| Image | Upload to a `DynamicTexture`; mipmaps + linear filtering by default ("pixelated" option for pixel art) |
| GIF / APNG | Decode all frames once (ImageIO for GIF; APNG via a small decoder), pack into a texture array/atlas, and pick the frame from replay time |
| Video | `FFmpegFrameGrabber` from Flashback's JavaCV. **Editor:** async decode thread with a small frame cache, seeking on scrub. **Export:** synchronous, frame-exact decode for each exported frame's timestamp, so output is deterministic. Audio is not mixed in v1 |
| Text (Minecraft) | Vanilla `Font` rendering (resource-pack fonts work), into world or screen pose |
| Text (TTF/OTF) | Rasterize glyphs with **FreeType via LWJGL**, which Minecraft already ships, into a high-res atlas (or SDF for crisp scaling) |
| Shapes | CPU-tessellated meshes (rounded rects, ellipses, arrows), solid or 2-stop gradient fill + stroke |

### Blend modes & effects

- Blend modes are separate `RenderPipeline`s (normal, additive, multiply, screen).
- Effects (shadow, outline, glow, blur) render the element to an offscreen target and then composite it with a shader
  pass. Groups with effects or group opacity render to an offscreen target too, so the group fades as one unit
  instead of each child fading separately.

---

## 7. Editor UI

All in ImGui, inside Flashback's editor:

- **Billboards window** (added to Flashback's window menu):
  - **Outliner:** a tree of groups and elements with drag-to-reorder/reparent, plus eye/lock toggles, duplicate,
    delete and "Save as preset".
  - **Add menu:** Image…, Media…, Text, Shape ▸, Group, From preset ▸. New world elements spawn ~3 blocks in front
    of the camera, facing it. New screen elements spawn in the frame centre.
  - **Inspector:** fields for the selected element with ◆ keyframe buttons. A ◆ is filled when the cursor sits on a
    keyframe and hollow when it doesn't.
- **Viewport gizmos (ImGuizmo):** click to select (ray-pick world quads, rect-hit screen elements), then translate /
  rotate / scale with `W`/`E`/`R`, local/world toggle, and snapping with `Ctrl`. With **auto-key** on, finishing a
  gizmo drag writes a keyframe at the cursor.
- **File picker:** Flashback's existing native dialog helper (or TinyFD from LWJGL) with image/video/font filters.
  Picked files are hashed and copied into `assets/`.

---

## 8. Project layout

```
src/main/java/me/zhdg/billboards/
  Billboards.java                     // ModInitializer (common bootstrap; nothing gameplay-side)
src/client/java/me/zhdg/billboards/
  BillboardsClient.java               // ClientModInitializer: registers keyframe types, hooks
  model/      Element, GroupElement, ImageElement, MediaElement, TextElement, ShapeElement, PropertySet, …
  project/    BillboardProject, ProjectManager (load/save/backup), AssetStore, PresetIO
  keyframe/   *KeyframeType, *Keyframe, *Change, KeyframeCodec
  runtime/    BillboardsRuntime (per-frame evaluated state), EntityAttachResolver
  render/     WorldOverlayRenderer, ScreenOverlayRenderer, pipelines/, text/, media/, shapes/, effects/
  editor/     BillboardsWindow, Outliner, Inspector, GizmoController, FilePicker
  mixin/      KeyframeTypeAdapterMixin, MinecraftKeyframeHandlerMixin, LevelRendererMixin,
              GameRendererMixin, EditorStateManagerMixin, WindowMenuMixin
```

Build changes: rename the template (`modid`/`com.example` → `billboards`/`me.zhdg.billboards`), add the Modrinth
Maven and `modCompileOnly "maven.modrinth:flashback:<version>"`, and run the dev client with Flashback as a
`modLocalRuntime` dependency.

---

## 9. Milestones

| # | Scope | Done when |
|---|---|---|
| **M1 — Core** | Template rename, Flashback dependency, data model + persistence, the 3 keyframe types incl. (de)serialization mixin, runtime evaluation, **world + screen Image and Text (Minecraft font)**, Billboards window (outliner + inspector + ◆ buttons), included in exports | An image fades in and slides across the screen and a world-space sign rotates, both keyframed on the timeline and identical in an exported MP4 |
| **M2 — Placement** | ImGuizmo gizmos, viewport picking, auto-key, facing modes, depth toggle, entity attach | Can drag elements in the viewport and pin a label above a player |
| **M3 — Shapes, TTF, crop** | Shape elements, custom TTF/OTF text, crop, typewriter reveal, groups + group opacity | Lower-third built from a shape + TTF text, animated as one group |
| **M4 — Animated media** | GIF/APNG, video via JavaCV with deterministic export decoding | Video clip plays in sync on a world screen in an export |
| **M5 — Polish** | Blend modes, effects (shadow/outline/glow/blur), presets, pack-for-sharing, missing-asset relink, localization | Preset saved in one replay and reused in another |

---

## 10. Risks & open items

- **Internal API churn:** the mixins target Flashback internals (§2). Mitigation: pin the version range, keep all
  Flashback-touching code in `keyframe/` and `mixin/`, and pursue an upstream API.
- **26.3 render API** (`renderpearl`) is new and lightly documented; Flashback's `WorldRenderHook` is the reference.
- **Video decode cost during export:** deterministic seeking can be slow for long-GOP files. A one-time transcode
  to an intra-frame proxy on import is the fallback.
- **Exports with Iris/shaders:** whether world overlays should go through shader passes needs testing (an
  "unaffected by shaders" option is likely).
- **To verify during M1:** the exact Modrinth Maven coordinates for Flashback 0.43.6 (the version number vs. the
  version id), which could not be fetched from this environment.
