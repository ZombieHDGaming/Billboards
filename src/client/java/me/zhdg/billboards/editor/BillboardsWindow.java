package me.zhdg.billboards.editor;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.utils.AsyncFileDialogs;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.ImGuiViewport;
import imgui.moulberry90.flag.ImGuiCond;
import imgui.moulberry90.flag.ImGuiTreeNodeFlags;
import imgui.moulberry90.type.ImBoolean;
import imgui.moulberry90.type.ImString;
import me.zhdg.billboards.Lang;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.keyframe.TrackEditor;
import me.zhdg.billboards.model.AttachAnchor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.BlendMode;
import me.zhdg.billboards.model.Effects;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Gradient;
import me.zhdg.billboards.model.ShapeKind;
import me.zhdg.billboards.model.Lighting;
import me.zhdg.billboards.model.TextAlign;
import me.zhdg.billboards.project.AssetStore;
import me.zhdg.billboards.project.Bundles;
import me.zhdg.billboards.project.ProjectManager;
import me.zhdg.billboards.render.CustomFont;
import me.zhdg.billboards.render.ElementGeometry;
import me.zhdg.billboards.render.ImageTextures;
import me.zhdg.billboards.render.MediaPlayer;
import me.zhdg.billboards.render.MediaPlayers;
import me.zhdg.billboards.render.SceneFrame;
import me.zhdg.billboards.render.OverlayRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The Billboards editor window: an outliner of the replay's overlays and an inspector for the selected one.
 * Rendered inside Flashback's ImGui frame (see {@code ReplayUIMixin}).
 */
public final class BillboardsWindow {

	public static final String WINDOW_ID = "billboards";
	private static final String[] IMAGE_EXTENSIONS = {"png", "jpg", "jpeg", "gif", "bmp"};
	private static final String[] FONT_EXTENSIONS = {"ttf", "otf"};
	private static final String[] VIDEO_EXTENSIONS = {"mp4", "mov", "mkv", "webm", "avi", "m4v", "gif"};

	private static final ImBoolean OPEN = new ImBoolean(true);
	private static @Nullable UUID presetSource = null;
	private static ImString presetName = ImGuiHelper.createResizableImString("");
	private static boolean openPresetPopup = false;
	private static @Nullable String statusMessage = null;
	private static long statusUntil = 0;

	private static @Nullable UUID nameBufferOwner = null;
	private static ImString nameBuffer = ImGuiHelper.createResizableImString("");

	private BillboardsWindow() {
	}

	// ------------------------------------------------------------------------------------------------
	// Open state: stored in Flashback's own "opened windows" config so it survives restarts.
	// ------------------------------------------------------------------------------------------------

	public static boolean isOpen() {
		FlashbackConfigV1 config = Flashback.getConfig();
		return config != null && config.internal.openedWindows.contains(WINDOW_ID);
	}

	public static void toggle() {
		setOpen(!isOpen());
	}

	private static void setOpen(boolean open) {
		FlashbackConfigV1 config = Flashback.getConfig();
		if (config == null) {
			return;
		}
		boolean changed = open ? config.internal.openedWindows.add(WINDOW_ID) : config.internal.openedWindows.remove(WINDOW_ID);
		if (changed) {
			config.delayedSaveToDefaultFolder();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Window
	// ------------------------------------------------------------------------------------------------

	public static void render() {
		if (!isOpen()) {
			return;
		}
		BillboardProject project = ProjectManager.getCurrent();
		UUID replayId = ProjectManager.currentReplayId();
		EditorState editorState = PlaybackTime.liveEditorState();
		if (project == null || replayId == null || editorState == null) {
			return;
		}

		// First use: along the right edge of the screen, so it doesn't cover the game frame.
		ImGuiViewport viewport = ImGui.getMainViewport();
		float width = Math.min(400, viewport.getSizeX() * 0.4f);
		ImGui.setNextWindowPos(viewport.getPosX() + viewport.getSizeX() - width, viewport.getPosY() + 30, ImGuiCond.FirstUseEver);
		ImGui.setNextWindowSize(width, Math.max(300, viewport.getSizeY() - 60), ImGuiCond.FirstUseEver);
		OPEN.set(true);
		if (ImGui.begin(Lang.tr("Billboards###billboards"), OPEN)) {
			try {
				renderAddMenu(project, replayId);
				renderFileMenus(project, replayId, editorState);
				renderMissingFiles(project, replayId);
				renderViewportToolbar();
				ImGui.separator();
				renderOutliner(project, editorState);
				ImGui.separator();
				Element element = Selection.get(project);
				if (element == null) {
					ImGui.textDisabled(Lang.tr("Select an overlay to edit it."));
				} else {
					renderInspector(project, replayId, editorState, element);
				}
			} catch (Exception e) {
				Billboards.LOGGER.error("Error in Billboards window", e);
			}
		}
		ImGui.end();
		if (!OPEN.get()) {
			setOpen(false);
		}
	}

	private static void renderAddMenu(BillboardProject project, UUID replayId) {
		// New overlays go 3 blocks in front of the camera, facing it (or into the selected group).
		if (ImGui.button(Lang.tr("Add image..."))) {
			pickImage(replayId, path -> addPicture(project, replayId, path, ElementType.IMAGE));
		}
		sameLineIfFits(Lang.tr("Add video..."));
		if (ImGui.button(Lang.tr("Add video..."))) {
			pickFile(replayId, Lang.tr("Videos"), VIDEO_EXTENSIONS, path -> addPicture(project, replayId, path, ElementType.VIDEO));
		}
		ImGuiHelper.tooltip(Lang.tr("A video file (MP4, MOV, MKV, WebM...) that plays in sync with the replay. Animated GIFs and PNGs can be added as images."));
		sameLineIfFits(Lang.tr("Add text"));
		if (ImGui.button(Lang.tr("Add text"))) {
			addText(project);
		}
		ImGuiHelper.tooltip(Lang.tr("Text that looks and behaves like a vanilla text display"));
		sameLineIfFits(Lang.tr("Add shape"));
		if (ImGui.button(Lang.tr("Add shape"))) {
			ImGui.openPopup("##billboards_add_shape");
		}
		sameLineIfFits(Lang.tr("Add group"));
		if (ImGui.button(Lang.tr("Add group"))) {
			addGroup(project);
		}
		ImGuiHelper.tooltip(Lang.tr("Groups move, rotate, scale and fade everything inside them together. Add things to a group by selecting it first, or with \"Move into group\" in the list."));

		if (ImGui.beginPopup("##billboards_add_shape")) {
			for (ShapeKind kind : ShapeKind.values()) {
				if (ImGui.menuItem(Lang.tr(kind.displayName))) {
					addShape(project, kind);
				}
			}
			ImGui.endPopup();
		}
	}

	private static void renderViewportToolbar() {
		ImGui.textDisabled(Lang.tr("Viewport:"));
		for (ViewportTools.Tool tool : ViewportTools.Tool.values()) {
			String label = Lang.tr(tool.displayName) + " (" + tool.key + ")";
			sameLineIfFits(label);
			if (ImGui.radioButton(label, ViewportTools.tool == tool)) {
				ViewportTools.tool = tool;
			}
		}
		if (ImGui.checkbox(Lang.tr("Local axes"), ViewportTools.localAxes)) {
			ViewportTools.localAxes = !ViewportTools.localAxes;
		}
		ImGuiHelper.tooltip(Lang.tr("Move and rotate along the overlay's own axes instead of the world's"));
		ImGui.sameLine();
		if (ImGui.checkbox(Lang.tr("Snap"), ViewportTools.snap)) {
			ViewportTools.snap = !ViewportTools.snap;
		}
		ImGuiHelper.tooltip(Lang.tr("Move in 0.25 block steps, rotate in 15° steps, scale in 0.1 steps"));
		ImGui.sameLine();
		if (ImGui.checkbox(Lang.tr("Auto-key"), ViewportTools.autoKey)) {
			ViewportTools.autoKey = !ViewportTools.autoKey;
		}
		ImGuiHelper.tooltip(Lang.tr("When on, editing a channel that isn't animated yet adds a keyframe at the cursor instead of changing its base value"));
		ImGui.textDisabled(Lang.tr("Click an overlay in the viewport to select it; drag the gizmo to edit it."));
	}

	private static void renderOutliner(BillboardProject project, EditorState editorState) {
		if (project.elements.isEmpty()) {
			ImGui.textDisabled(Lang.tr("No overlays yet. Add an image, text, a shape or a group."));
			return;
		}

		ImGui.beginChild("##billboards_outliner", 0, 170, true);
		Runnable[] action = {null};
		renderOutlinerLevel(project, editorState, null, 0, action);
		ImGui.endChild();
		if (action[0] != null) {
			action[0].run();
			project.markDirty();
		}
	}

	/** One level of the outliner tree; the clicked context-menu action is stored in {@code action} and run afterwards. */
	private static void renderOutlinerLevel(BillboardProject project, EditorState editorState, @Nullable UUID parentId, int depth, Runnable[] action) {
		List<Element> siblings = project.children(parentId);
		for (int i = 0; i < siblings.size(); i++) {
			Element element = siblings.get(i);
			ImGui.pushID(element.id.toString());

			if (depth > 0) {
				ImGui.indent(depth * 14f);
			}
			if (ImGui.checkbox("##visible", !element.hidden)) {
				element.hidden = !element.hidden;
				project.markDirty();
			}
			ImGuiHelper.tooltip(element.type == ElementType.GROUP ? Lang.tr("Show or hide this group and everything in it") : Lang.tr("Show or hide this overlay (also in exports)"));
			ImGui.sameLine();

			String label = (element.type == ElementType.GROUP ? "[+] " : "") + element.name + "  (" + Lang.tr(element.type.displayName) + ")";
			if (ImGui.selectable(label, Selection.is(element.id))) {
				Selection.set(element.id);
			}
			if (depth > 0) {
				ImGui.unindent(depth * 14f);
			}

			if (ImGui.beginPopupContextItem("##context")) {
				Element previous = i > 0 ? siblings.get(i - 1) : null;
				Element next = i < siblings.size() - 1 ? siblings.get(i + 1) : null;
				if (ImGui.menuItem(Lang.tr("Duplicate"))) {
					action[0] = () -> Selection.set(duplicateTree(project, editorState, element, element.parentId).id);
				}
				if (ImGui.menuItem(Lang.tr("Save as preset..."))) {
					presetSource = element.id;
					presetName = ImGuiHelper.createResizableImString(element.name);
					openPresetPopup = true;
				}
				if (ImGui.menuItem(Lang.tr("Move up"), "", false, previous != null)) {
					action[0] = () -> swap(project, element, previous);
				}
				if (ImGui.menuItem(Lang.tr("Move down"), "", false, next != null)) {
					action[0] = () -> swap(project, element, next);
				}
				List<Element> groups = project.elements.stream()
					.filter(g -> g.type == ElementType.GROUP && !project.isWithin(g, element.id) && !g.id.equals(element.parentId))
					.toList();
				if (ImGui.beginMenu(Lang.tr("Move into group"), !groups.isEmpty())) {
					for (Element group : groups) {
						if (ImGui.menuItem(group.name + "##" + group.id)) {
							action[0] = () -> reparent(project, editorState, element, group);
						}
					}
					ImGui.endMenu();
				}
				if (element.parentId != null && ImGui.menuItem(Lang.tr("Move out of group"))) {
					Element group = project.get(element.parentId);
					Element outer = group == null || group.parentId == null ? null : project.get(group.parentId);
					action[0] = () -> reparent(project, editorState, element, outer);
				}
				if (element.type == ElementType.GROUP && ImGui.menuItem(Lang.tr("Ungroup"))) {
					action[0] = () -> ungroup(project, editorState, element);
				}
				ImGui.separator();
				if (ImGui.menuItem(element.type == ElementType.GROUP ? Lang.tr("Delete group and contents") : Lang.tr("Delete"))) {
					action[0] = () -> deleteTree(project, editorState, element);
				}
				ImGui.endPopup();
			}
			ImGui.popID();

			if (element.type == ElementType.GROUP) {
				renderOutlinerLevel(project, editorState, element.id, depth + 1, action);
			}
		}
	}

	/** Swaps two siblings' places in the project's list (which is also their draw order within a layer). */
	private static void swap(BillboardProject project, Element a, Element b) {
		int indexA = project.elements.indexOf(a);
		int indexB = project.elements.indexOf(b);
		project.elements.set(indexA, b);
		project.elements.set(indexB, a);
	}

	/** Moves an element into a group (or to the top level), keeping it where it is in the world. */
	private static void reparent(BillboardProject project, EditorState editorState, Element element, @Nullable Element newParent) {
		SceneFrame scene = SceneFrame.current();
		if (scene != null) {
			SceneFrame.Node node = scene.node(element.id);
			SceneFrame.Node parentNode = newParent == null ? null : scene.node(newParent.id);
			int tick = PlaybackTime.cursorTick();
			boolean animated = tick >= 0 && TrackEditor.keyState(editorState, element.id, Channel.TRANSFORM, tick) != TrackEditor.KeyState.NOT_ANIMATED;
			if (node != null && !animated) {
				ChannelValues transform = ViewportTools.reparentedTransform(scene, node, parentNode);
				if (transform != null) {
					element.transform = transform;
				}
			}
		}
		element.parentId = newParent == null ? null : newParent.id;
		if (newParent != null) {
			// Attachment belongs to the group now.
			element.attachEntity = null;
			element.attachEntityName = null;
		}
	}

	private static void ungroup(BillboardProject project, EditorState editorState, Element group) {
		Element outer = group.parentId == null ? null : project.get(group.parentId);
		for (Element child : project.children(group.id)) {
			reparent(project, editorState, child, outer);
		}
		deleteTree(project, editorState, group);
	}

	private static void deleteTree(BillboardProject project, EditorState editorState, Element root) {
		// Find everything first: once the group is removed, its contents no longer look like they're inside it.
		List<Element> doomed = project.elements.stream().filter(element -> project.isWithin(element, root.id)).toList();
		for (Element element : doomed) {
			project.elements.remove(element);
			TrackEditor.removeTracks(editorState, element.id);
			if (Selection.is(element.id)) {
				Selection.set(null);
			}
		}
	}

	/** Copies an element (and, for a group, everything in it) with its keyframe tracks. Returns the copy. */
	private static Element duplicateTree(BillboardProject project, EditorState editorState, Element source, @Nullable UUID parentId) {
		Element copy = source.copy();
		copy.parentId = parentId;
		if (Objects.equals(parentId, source.parentId)) {
			copy.name = Lang.tr("%s copy", source.name);
		}
		int index = project.elements.indexOf(source);
		project.elements.add(index < 0 ? project.elements.size() : index + 1, copy);
		TrackEditor.duplicateTracks(editorState, source.id, copy);
		for (Element child : project.children(source.id)) {
			if (child != copy) {
				duplicateTree(project, editorState, child, copy.id);
			}
		}
		return copy;
	}

	// ------------------------------------------------------------------------------------------------
	// Presets, packs and missing files
	// ------------------------------------------------------------------------------------------------

	private static void status(String message) {
		statusMessage = message;
		statusUntil = System.currentTimeMillis() + 6000;
	}

	private static void renderFileMenus(BillboardProject project, UUID replayId, EditorState editorState) {
		if (ImGui.button(Lang.tr("Presets"))) {
			ImGui.openPopup("##billboards_presets");
		}
		ImGuiHelper.tooltip(Lang.tr("Reuse overlays (with their animation) in any replay. Save one by right-clicking it in the list."));
		sameLineIfFits(Lang.tr("Share"));
		if (ImGui.button(Lang.tr("Share"))) {
			ImGui.openPopup("##billboards_share");
		}
		ImGuiHelper.tooltip(Lang.tr("Flashback keeps editor data outside the replay file, so to give someone a replay with its overlays, send a pack too"));

		if (ImGui.beginPopup("##billboards_presets")) {
			List<String> presets = Bundles.presetNames();
			if (presets.isEmpty()) {
				ImGui.textDisabled(Lang.tr("No presets yet. Right-click an overlay or group in the list and choose \"Save as preset...\""));
			}
			for (String name : presets) {
				if (ImGui.beginMenu(name + "##preset_" + name)) {
					if (ImGui.menuItem(Lang.tr("Add at the cursor"))) {
						insertPreset(project, replayId, editorState, name);
					}
					if (ImGui.menuItem(Lang.tr("Delete preset"))) {
						try {
							Files.deleteIfExists(Bundles.presetPath(name));
							status(Lang.tr("Deleted preset %s", name));
						} catch (Exception e) {
							Billboards.LOGGER.error("Unable to delete preset {}", name, e);
						}
					}
					ImGui.endMenu();
				}
			}
			ImGui.endPopup();
		}

		if (ImGui.beginPopup("##billboards_share")) {
			if (ImGui.menuItem(Lang.tr("Export pack..."))) {
				String start = FabricLoader.getInstance().getGameDir().toString();
				AsyncFileDialogs.saveFileDialog(start, "billboards-pack.zip", "Billboards pack", "zip").thenAccept(picked -> {
					if (picked != null) {
						Minecraft.getInstance().execute(() -> exportPack(project, replayId, editorState, Path.of(picked)));
					}
				});
			}
			ImGuiHelper.tooltip(Lang.tr("Saves every overlay in this replay, their files and their keyframes into one file"));
			if (ImGui.menuItem(Lang.tr("Import pack..."))) {
				pickFile(replayId, Lang.tr("Billboards pack"), new String[]{"zip"}, path -> importPack(project, replayId, editorState, path));
			}
			ImGuiHelper.tooltip(Lang.tr("Adds the overlays and keyframes from a pack to this replay"));
			ImGui.endPopup();
		}

		if (statusMessage != null && System.currentTimeMillis() < statusUntil) {
			ImGui.textDisabled(statusMessage);
		}

		// "Save as preset" name prompt.
		if (openPresetPopup) {
			ImGui.openPopup("Save preset###billboards_save_preset");
			openPresetPopup = false;
		}
		if (ImGui.beginPopupModal(Lang.tr("Save preset###billboards_save_preset"))) {
			ImGui.inputText(Lang.tr("Name"), presetName);
			if (ImGui.button(Lang.tr("Save"))) {
				savePreset(project, replayId, editorState, ImGuiHelper.getString(presetName));
				ImGui.closeCurrentPopup();
			}
			ImGui.sameLine();
			if (ImGui.button(Lang.tr("Cancel"))) {
				ImGui.closeCurrentPopup();
			}
			ImGui.endPopup();
		}
	}

	static void savePreset(BillboardProject project, UUID replayId, EditorState editorState, String name) {
		Element source = presetSource == null ? null : project.get(presetSource);
		if (source == null || name.isBlank()) {
			return;
		}
		try {
			Bundles.write(Bundles.presetPath(name), replayId, project, editorState, List.of(source), true);
			status(Lang.tr("Saved preset %s", name));
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to save preset {}", name, e);
			status(Lang.tr("Couldn't save the preset; see the log"));
		}
	}

	private static void insertPreset(BillboardProject project, UUID replayId, EditorState editorState, String name) {
		try {
			Path file = Bundles.presetPath(name);
			Bundles.Bundle bundle = Bundles.read(file);
			Element probe = new Element();
			place(probe);
			Vec3 inFront = new Vec3(probe.transform.get(Channel.X), probe.transform.get(Channel.Y), probe.transform.get(Channel.Z));
			List<Element> roots = Bundles.insert(file, bundle, replayId, project, editorState, Math.max(0, PlaybackTime.cursorTick()), inFront);
			if (!roots.isEmpty()) {
				Selection.set(roots.getFirst().id);
			}
			status(Lang.tr("Added preset %s", name));
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to add preset {}", name, e);
			status(Lang.tr("Couldn't add the preset; see the log"));
		}
	}

	private static void exportPack(BillboardProject project, UUID replayId, EditorState editorState, Path file) {
		try {
			Bundles.write(file, replayId, project, editorState, project.children(null), false);
			status(Lang.tr("Exported %s overlays to %s", project.elements.size(), file.getFileName()));
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to export pack", e);
			status(Lang.tr("Couldn't export the pack; see the log"));
		}
	}

	private static void importPack(BillboardProject project, UUID replayId, EditorState editorState, Path file) {
		try {
			Bundles.Bundle bundle = Bundles.read(file);
			Bundles.insert(file, bundle, replayId, project, editorState, 0, null);
			status(Lang.tr("Imported %s overlays from %s", bundle.elements().size(), file.getFileName()));
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to import pack {}", file, e);
			status("Couldn't import the pack; see the log");
		}
	}

	/** Lists files the project uses that are missing from its assets folder. */
	private static void renderMissingFiles(BillboardProject project, UUID replayId) {
		java.util.Set<String> missing = new java.util.LinkedHashSet<>();
		for (Element element : project.elements) {
			for (String assetId : new String[]{element.assetId, element.fontAssetId}) {
				if (assetId != null && !AssetStore.exists(replayId, assetId)) {
					missing.add(assetId);
				}
			}
		}
		if (missing.isEmpty()) {
			return;
		}
		ImGui.textColored(0xFF5555FF, missing.size() == 1 ? Lang.tr("1 file is missing") : Lang.tr("%s files are missing", missing.size()));
		ImGuiHelper.tooltip(Lang.tr("Shown as a magenta checkerboard. Select the overlay and use Relink, or relink here."));
		for (String assetId : missing) {
			ImGui.pushID(assetId);
			ImGui.bulletText(project.assets.getOrDefault(assetId, assetId));
			ImGui.sameLine();
			if (ImGui.smallButton(Lang.tr("Relink..."))) {
				pickFile(replayId, Lang.tr("Files"), new String[]{"png", "jpg", "jpeg", "gif", "bmp", "mp4", "mov", "mkv", "webm", "avi", "m4v", "ttf", "otf"},
					path -> relink(project, replayId, assetId, path));
			}
			ImGui.popID();
		}
	}

	/** Points every use of a missing (or any) asset at a newly picked file. */
	public static void relink(BillboardProject project, UUID replayId, String oldAssetId, Path path) {
		try {
			String newAssetId = AssetStore.importFile(replayId, project, path);
			for (Element element : project.elements) {
				if (oldAssetId.equals(element.assetId)) {
					element.assetId = newAssetId;
				}
				if (oldAssetId.equals(element.fontAssetId)) {
					element.fontAssetId = newAssetId;
				}
			}
			ImageTextures.retry(newAssetId);
			MediaPlayers.retry(newAssetId);
			CustomFont.retry(newAssetId);
			project.markDirty();
			status(Lang.tr("Relinked %s", path.getFileName()));
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to relink {}", path, e);
			status(Lang.tr("Couldn't relink; see the log"));
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Inspector
	// ------------------------------------------------------------------------------------------------

	private static void renderInspector(BillboardProject project, UUID replayId, EditorState editorState, Element element) {
		ImGui.pushID(element.id.toString());
		ImGui.pushItemWidth(220);

		if (!element.id.equals(nameBufferOwner)) {
			nameBufferOwner = element.id;
			nameBuffer = ImGuiHelper.createResizableImString(element.name);
		}
		if (ImGui.inputText(Lang.tr("Name"), nameBuffer)) {
			element.name = ImGuiHelper.getString(nameBuffer);
			project.markDirty();
		}
		if (ImGui.isItemDeactivatedAfterEdit()) {
			TrackEditor.renameTracks(editorState, element);
		}

		if (ImGui.collapsingHeader(Lang.tr("Settings"), ImGuiTreeNodeFlags.DefaultOpen)) {
			if (renderSettings(project, replayId, element)) {
				project.markDirty();
			}
		}
		if (element.type != ElementType.GROUP && ImGui.collapsingHeader(Lang.tr("Look"))) {
			if (renderLookSettings(element)) {
				project.markDirty();
			}
		}

		int tick = PlaybackTime.cursorTick();
		KeyframeEvaluator.Frame frame = KeyframeEvaluator.evaluate(PlaybackTime.current());
		for (Channel channel : Channel.values()) {
			if (channel != Channel.CONTENT || element.type.hasContent()) {
				renderChannel(project, editorState, element, channel, frame, tick);
			}
		}

		ImGui.popItemWidth();
		ImGui.popID();
	}

	/** Static (non-animated) settings. Returns true if anything changed. */
	private static boolean renderSettings(BillboardProject project, UUID replayId, Element element) {
		boolean changed = false;

		Facing facing = enumCombo(Lang.tr("Billboard"), element.facing, Facing.values(), f -> f.displayName);
		if (facing != element.facing) {
			element.facing = facing;
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("How the overlay turns towards the camera, like a display entity's billboard setting. Fixed uses its own rotation; the others follow the camera on some axes"));
		if (ImGui.checkbox(Lang.tr("See through blocks"), !element.depthTest)) {
			element.depthTest = !element.depthTest;
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("Draw the overlay on top of blocks and entities, like a see-through text display"));
		if (element.type != ElementType.GROUP) {
			changed |= renderLighting(element);
		}
		if (element.parentId == null && renderAttachment(element)) {
			changed = true;
		}

		if (element.type != ElementType.GROUP) {
			float[] pivot = {element.pivotX, element.pivotY};
			if (ImGui.dragFloat2(Lang.tr("Pivot (0-1)"), pivot, 0.005f, 0f, 1f, "%.3f")) {
				element.pivotX = pivot[0];
				element.pivotY = pivot[1];
				changed = true;
			}
			ImGuiHelper.tooltip(Lang.tr("The point of the overlay that sits on its position and that it rotates around. 0.5, 0.5 is the centre"));
		} else {
			ImGui.textDisabled(Lang.tr("Position, rotation, scale, opacity and tint apply to everything in the group."));
		}

		if (element.parentId == null) {
			int[] layer = {element.layer};
			if (ImGuiHelper.inputInt(Lang.tr("Layer"), layer)) {
				element.layer = layer[0];
				changed = true;
			}
			ImGuiHelper.tooltip(Lang.tr("Draw order of see-through overlays (higher on top); the rest are sorted by distance"));
		} else {
			ImGui.textDisabled(Lang.tr("Inside a group: position is relative to the group."));
		}

		String unit = Lang.tr("blocks");
		if (element.type.isPicture()) {
			boolean video = element.type == ElementType.VIDEO;
			String fileName = element.assetId == null ? "(none)" : project.assets.getOrDefault(element.assetId, element.assetId);
			ImGui.text(video ? Lang.tr("Video: %s", fileName) : Lang.tr("Image: %s", fileName));
			if (element.assetId != null && !AssetStore.exists(replayId, element.assetId)) {
				ImGui.textColored(0xFF5555FF, Lang.tr("File missing"));
				ImGui.sameLine();
				if (ImGui.smallButton(Lang.tr("Relink..."))) {
					String old = element.assetId;
					pickFile(replayId, video ? Lang.tr("Videos") : Lang.tr("Images"), video ? VIDEO_EXTENSIONS : IMAGE_EXTENSIONS, path -> relink(project, replayId, old, path));
				}
			}
			ImGui.sameLine();
			if (ImGui.smallButton(Lang.tr("Replace..."))) {
				pickFile(replayId, video ? Lang.tr("Videos") : Lang.tr("Images"), video ? VIDEO_EXTENSIONS : IMAGE_EXTENSIONS,
					path -> replaceImage(project, replayId, element, path));
			}
			float[] size = {element.width, element.height};
			if (ImGui.dragFloat2(Lang.tr("Size (%s)", unit), size, 0.01f, 0.001f, 10000f, "%.3f")) {
				element.width = size[0];
				element.height = size[1];
				changed = true;
			}
			if (ImGui.checkbox(Lang.tr("Pixelated"), element.pixelated)) {
				element.pixelated = !element.pixelated;
				changed = true;
			}
			ImGuiHelper.tooltip(Lang.tr("Use nearest-neighbour filtering, for pixel art"));
			if (MediaPlayers.isPlayed(replayId, element)) {
				changed |= renderPlaybackSettings(replayId, element);
			}
		} else if (element.type == ElementType.SHAPE) {
			changed |= renderShapeSettings(element, unit);
		} else if (element.type == ElementType.TEXT) {
			String fontName = element.fontAssetId == null ? "Minecraft" : project.assets.getOrDefault(element.fontAssetId, element.fontAssetId);
			ImGui.text(Lang.tr("Font: %s", fontName));
			if (element.fontAssetId != null && !AssetStore.exists(replayId, element.fontAssetId)) {
				ImGui.textColored(0xFF5555FF, Lang.tr("Font file missing (using Minecraft's font)"));
				ImGui.sameLine();
				if (ImGui.smallButton(Lang.tr("Relink...##font"))) {
					String old = element.fontAssetId;
					pickFile(replayId, Lang.tr("Fonts"), FONT_EXTENSIONS, path -> relink(project, replayId, old, path));
				}
			}
			ImGui.sameLine();
			if (ImGui.smallButton(Lang.tr("Choose..."))) {
				pickFile(replayId, Lang.tr("Fonts"), FONT_EXTENSIONS, path -> setFont(project, replayId, element, path));
			}
			ImGuiHelper.tooltip(Lang.tr("Use a TTF or OTF font file instead of Minecraft's font"));
			if (element.fontAssetId != null) {
				ImGui.sameLine();
				if (ImGui.smallButton(Lang.tr("Minecraft font"))) {
					element.fontAssetId = null;
					changed = true;
				}
			}
			int[] lineWidth = {element.lineWidth};
			if (ImGuiHelper.inputInt(Lang.tr("Line width"), lineWidth)) {
				element.lineWidth = Math.max(1, lineWidth[0]);
				changed = true;
			}
			ImGuiHelper.tooltip(Lang.tr("Lines longer than this wrap, in font pixels (40 pixels = 1 block). Vanilla's default is 200. Make text bigger with the Scale channel"));
			TextAlign align = enumCombo(Lang.tr("Align"), element.align, TextAlign.values(), a -> a.displayName);
			if (align != element.align) {
				element.align = align;
				changed = true;
			}
			if (ImGui.checkbox(Lang.tr("Shadow"), element.textShadow)) {
				element.textShadow = !element.textShadow;
				changed = true;
			}
			float[] background = argbToFloats(element.backgroundColor);
			if (ImGui.colorEdit4(Lang.tr("Background"), background)) {
				element.backgroundColor = floatsToArgb(background);
				changed = true;
			}
			if (element.backgroundColor != DEFAULT_TEXT_BACKGROUND) {
				ImGui.sameLine();
				if (ImGui.smallButton(Lang.tr("Default##background"))) {
					element.backgroundColor = DEFAULT_TEXT_BACKGROUND;
					changed = true;
				}
				ImGuiHelper.tooltip(Lang.tr("Vanilla's default text background: black, a quarter opaque"));
			}
		}
		return changed;
	}

	/** Vanilla text displays' default background (the default "text background opacity" of 25%). */
	private static final int DEFAULT_TEXT_BACKGROUND = 0x40000000;

	/** How the overlay is lit. Returns true if anything changed. */
	private static boolean renderLighting(Element element) {
		boolean changed = false;
		Lighting lighting = enumCombo(Lang.tr("Lighting"), element.lighting, Lighting.values(), l -> l.displayName);
		if (lighting != element.lighting) {
			element.lighting = lighting;
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("World light darkens the overlay at night and in caves, like a vanilla text display. Custom levels work like a display entity's brightness override"));
		if (element.lighting == Lighting.CUSTOM) {
			int[] block = {element.blockLight};
			if (ImGui.sliderInt(Lang.tr("Block light"), block, 0, 15)) {
				element.blockLight = block[0];
				changed = true;
			}
			int[] sky = {element.skyLight};
			if (ImGui.sliderInt(Lang.tr("Sky light"), sky, 0, 15)) {
				element.skyLight = sky[0];
				changed = true;
			}
		}
		return changed;
	}

	/** Entity attachment settings for a world element. Returns true if anything changed. */
	private static boolean renderAttachment(Element element) {
		boolean changed = false;
		EditorState editorState = PlaybackTime.liveEditorState();
		int tick = PlaybackTime.cursorTick();
		boolean positionAnimated = editorState != null && tick >= 0
			&& TrackEditor.keyState(editorState, element.id, Channel.TRANSFORM, tick) != TrackEditor.KeyState.NOT_ANIMATED;

		if (element.attachEntity == null) {
			if (ImGui.button(Lang.tr("Attach to entity..."))) {
				ImGui.openPopup("##billboards_attach");
			}
			ImGuiHelper.tooltip(positionAnimated
				? Lang.tr("Follow an entity. Position keyframes will then be offsets from the entity.")
				: Lang.tr("Follow an entity, e.g. to pin a label above a player's head. The position becomes an offset from the entity."));
			if (ImGui.beginPopup("##billboards_attach")) {
				List<Entity> candidates = attachCandidates();
				if (candidates.isEmpty()) {
					ImGui.textDisabled(Lang.tr("No entities nearby"));
				}
				Vec3 eye = Minecraft.getInstance().gameRenderer.mainCamera().position();
				for (Entity entity : candidates) {
					String label = Lang.tr("%s  (%s, %s blocks)", entity.getName().getString(), entity.getType().getDescription().getString(),
						Math.round(entity.position().distanceTo(eye))) + "##" + entity.getUUID();
					if (ImGui.selectable(label)) {
						element.attachEntity = entity.getUUID();
						element.attachEntityName = entity.getName().getString();
						if (!positionAnimated) {
							// Start right on the anchor (above the head by default), like a name tag.
							element.transform.set(Channel.X, 0);
							element.transform.set(Channel.Y, 0);
							element.transform.set(Channel.Z, 0);
						}
						changed = true;
						ImGui.closeCurrentPopup();
					}
				}
				ImGui.endPopup();
			}
			return changed;
		}

		Entity entity = ElementGeometry.attachedEntity(element);
		String name = entity != null ? entity.getName().getString() : (element.attachEntityName == null ? "?" : element.attachEntityName);
		ImGui.text(Lang.tr("Attached to %s", name) + (entity == null ? " " + Lang.tr("(not loaded at this tick)") : ""));
		ImGui.sameLine();
		if (ImGui.smallButton(Lang.tr("Detach"))) {
			if (entity != null && !positionAnimated) {
				// Keep the overlay where it is now.
				Vec3 world = ElementGeometry.worldPosition(element, element.transform);
				if (world != null) {
					element.transform.set(Channel.X, world.x);
					element.transform.set(Channel.Y, world.y);
					element.transform.set(Channel.Z, world.z);
				}
			}
			element.attachEntity = null;
			element.attachEntityName = null;
			return true;
		}
		ImGuiHelper.tooltip(positionAnimated
			? Lang.tr("Position keyframes stay as they are and become world coordinates again")
			: Lang.tr("Stop following the entity, leaving the overlay where it is now"));

		AttachAnchor anchor = enumCombo(Lang.tr("Anchor"), element.attachAnchor, AttachAnchor.values(), a -> a.displayName);
		if (anchor != element.attachAnchor) {
			element.attachAnchor = anchor;
			changed = true;
		}
		if (ImGui.checkbox(Lang.tr("Turn with entity"), element.attachRotate)) {
			element.attachRotate = !element.attachRotate;
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("Rotate the offset (and a fixed-facing overlay) with the entity's body"));
		ImGui.textDisabled(Lang.tr("Position is an offset from the anchor while attached."));
		return changed;
	}

	/** Entities near the camera to attach to: players first, then by distance. */
	private static List<Entity> attachCandidates() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return List.of();
		}
		Vec3 eye = minecraft.gameRenderer.mainCamera().position();
		List<Entity> result = new ArrayList<>();
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (entity == minecraft.player || entity == minecraft.getCameraEntity() || entity.position().distanceToSqr(eye) > 128 * 128) {
				continue;
			}
			result.add(entity);
		}
		result.sort(Comparator.comparing((Entity e) -> !(e instanceof Player)).thenComparingDouble(e -> e.position().distanceToSqr(eye)));
		return result.size() > 40 ? result.subList(0, 40) : result;
	}

	private static void renderChannel(BillboardProject project, EditorState editorState, Element element, Channel channel,
	                                  KeyframeEvaluator.Frame frame, int tick) {
		if (!ImGui.collapsingHeader(Lang.tr(channel.displayName) + "##" + channel.id(), ImGuiTreeNodeFlags.DefaultOpen)) {
			return;
		}
		ImGui.pushID(channel.id());

		TrackEditor.KeyState state = tick < 0 ? TrackEditor.KeyState.NOT_ANIMATED : TrackEditor.keyState(editorState, element.id, channel, tick);
		switch (state) {
			case NOT_ANIMATED -> {
				if (ImGui.smallButton(Lang.tr("Animate"))) {
					TrackEditor.setKeyframe(editorState, element, element.base(channel), tick);
				}
				ImGuiHelper.tooltip(Lang.tr("Add a keyframe at the cursor (tick %s) and show this channel on the timeline", tick));
				ImGui.sameLine();
				ImGui.textDisabled(ViewportTools.autoKey
					? Lang.tr("Not animated: auto-key adds a keyframe at tick %s", tick)
					: Lang.tr("Not animated: edits change the base value"));
			}
			case ANIMATED -> {
				if (ImGui.smallButton(Lang.tr("Add keyframe"))) {
					TrackEditor.setKeyframe(editorState, element, OverlayRenderer.valuesOf(frame, element, channel), tick);
				}
				ImGui.sameLine();
				ImGui.textDisabled(Lang.tr("Animated: edits add a keyframe at tick %s", tick));
			}
			case ON_KEYFRAME -> {
				if (ImGui.smallButton(Lang.tr("Remove keyframe"))) {
					TrackEditor.removeKeyframe(editorState, element.id, channel, tick);
				}
				ImGui.sameLine();
				ImGui.textDisabled(Lang.tr("Editing the keyframe at tick %s", tick));
			}
		}

		ChannelValues shown = (state == TrackEditor.KeyState.NOT_ANIMATED ? element.base(channel) : OverlayRenderer.valuesOf(frame, element, channel)).copy();
		boolean changed = ChannelEditor.render(shown, shown::set, text -> shown.text = text);
		if (changed) {
			ViewportTools.commitValues(project, editorState, element, shown);
		}
		ImGui.popID();
	}

	// ------------------------------------------------------------------------------------------------
	// Creating elements
	// ------------------------------------------------------------------------------------------------

	/** Timing of an animated image or a video. Returns true if anything changed. */
	private static boolean renderPlaybackSettings(UUID replayId, Element element) {
		boolean changed = false;
		MediaPlayer player = MediaPlayers.player(replayId, element);
		if (player != null) {
			MediaPlayer.Info info = player.info();
			ImGui.textDisabled(String.format(java.util.Locale.ROOT, "%dx%d, %.2f s at %.1f fps", info.width(), info.height(), info.durationSeconds(), info.frameRate()));
		} else {
			ImGui.textDisabled(Lang.tr("Can't be played; see the log"));
		}
		int[] start = {element.mediaStartTick};
		if (ImGuiHelper.inputInt(Lang.tr("Starts at tick"), start)) {
			element.mediaStartTick = Math.max(0, start[0]);
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("Replay tick at which it starts playing; before that it shows its first frame"));
		ImGui.sameLine();
		if (ImGui.smallButton(Lang.tr("Cursor"))) {
			element.mediaStartTick = Math.max(0, PlaybackTime.cursorTick());
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("Start playing at the timeline cursor"));
		float[] speed = {element.mediaSpeed};
		if (ImGui.dragFloat(Lang.tr("Speed"), speed, 0.01f, 0.01f, 100f, "%.2fx")) {
			element.mediaSpeed = Math.max(0.01f, speed[0]);
			changed = true;
		}
		if (ImGui.checkbox(Lang.tr("Loop"), element.mediaLoop)) {
			element.mediaLoop = !element.mediaLoop;
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("Start again after the end instead of holding the last frame"));
		return changed;
	}

	/** Blend mode and effects. Returns true if anything changed. */
	private static boolean renderLookSettings(Element element) {
		boolean changed = false;
		String unit = Lang.tr("blocks");
		BlendMode blend = enumCombo(Lang.tr("Blend"), element.blend, BlendMode.values(), b -> b.displayName);
		if (blend != element.blend) {
			element.blend = blend;
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("Additive adds light (glows, beams); multiply darkens; screen lightens"));

		Effects effects = element.effects;
		ImGui.pushID("shadow");
		if (ImGui.checkbox(Lang.tr("Drop shadow"), effects.shadow)) {
			effects.shadow = !effects.shadow;
			changed = true;
		}
		if (effects.shadow) {
			changed |= colour(Lang.tr("Colour"), effects.shadowColor, c -> effects.shadowColor = c);
			float[] offset = {effects.shadowOffsetX, effects.shadowOffsetY};
			if (ImGui.dragFloat2(Lang.tr("Offset right, down (%s)", unit), offset, 0.0005f, -100f, 100f, "%.4f")) {
				effects.shadowOffsetX = offset[0];
				effects.shadowOffsetY = offset[1];
				changed = true;
			}
			float[] softness = {effects.shadowSoftness};
			if (ImGui.dragFloat(Lang.tr("Softness (%s)", unit), softness, 0.0005f, 0f, 100f, "%.4f")) {
				effects.shadowSoftness = Math.max(0, softness[0]);
				changed = true;
			}
		}
		ImGui.popID();

		ImGui.pushID("outline");
		if (ImGui.checkbox(Lang.tr("Outline"), effects.outline)) {
			effects.outline = !effects.outline;
			changed = true;
		}
		if (effects.outline) {
			changed |= colour(Lang.tr("Colour"), effects.outlineColor, c -> effects.outlineColor = c);
			float[] width = {effects.outlineWidth};
			if (ImGui.dragFloat(Lang.tr("Width (%s)", unit), width, 0.0005f, 0f, 100f, "%.4f")) {
				effects.outlineWidth = Math.max(0, width[0]);
				changed = true;
			}
		}
		ImGui.popID();

		ImGui.pushID("glow");
		if (ImGui.checkbox(Lang.tr("Glow"), effects.glow)) {
			effects.glow = !effects.glow;
			changed = true;
		}
		if (effects.glow) {
			changed |= colour(Lang.tr("Colour"), effects.glowColor, c -> effects.glowColor = c);
			float[] radius = {effects.glowRadius};
			if (ImGui.dragFloat(Lang.tr("Radius (%s)", unit), radius, 0.001f, 0f, 100f, "%.4f")) {
				effects.glowRadius = Math.max(0, radius[0]);
				changed = true;
			}
		}
		ImGui.popID();

		if (element.type.isPicture()) {
			float[] blur = {effects.blur};
			if (ImGui.dragFloat(Lang.tr("Blur (%s)", unit), blur, 0.0005f, 0f, 100f, "%.4f")) {
				effects.blur = Math.max(0, blur[0]);
				changed = true;
			}
			ImGuiHelper.tooltip(Lang.tr("Blurs the image or video; 0 for none"));
		}
		return changed;
	}

	private static boolean colour(String label, int argb, java.util.function.IntConsumer set) {
		float[] rgba = argbToFloats(argb);
		if (ImGui.colorEdit4(label, rgba)) {
			set.accept(floatsToArgb(rgba));
			return true;
		}
		return false;
	}

	private static boolean renderShapeSettings(Element element, String unit) {
		boolean changed = false;
		ShapeKind kind = enumCombo(Lang.tr("Shape"), element.shapeKind, ShapeKind.values(), k -> k.displayName);
		if (kind != element.shapeKind) {
			element.shapeKind = kind;
			changed = true;
		}
		float[] size = {element.width, element.height};
		if (ImGui.dragFloat2(Lang.tr("Size (%s)", unit), size, 0.005f, 0.001f, 10000f, "%.3f")) {
			element.width = size[0];
			element.height = size[1];
			changed = true;
		}
		float[] fill = argbToFloats(element.fillColor);
		if (ImGui.colorEdit4(element.gradient == Gradient.NONE ? Lang.tr("Fill") : Lang.tr("Fill (start)"), fill)) {
			element.fillColor = floatsToArgb(fill);
			changed = true;
		}
		Gradient gradient = enumCombo(Lang.tr("Gradient"), element.gradient, Gradient.values(), g -> g.displayName);
		if (gradient != element.gradient) {
			element.gradient = gradient;
			changed = true;
		}
		if (element.gradient != Gradient.NONE) {
			float[] fill2 = argbToFloats(element.fillColor2);
			if (ImGui.colorEdit4(Lang.tr("Fill (end)"), fill2)) {
				element.fillColor2 = floatsToArgb(fill2);
				changed = true;
			}
		}
		boolean lineLike = element.shapeKind == ShapeKind.LINE || element.shapeKind == ShapeKind.ARROW;
		float[] stroke = {element.strokeWidth};
		if (ImGui.dragFloat(lineLike ? Lang.tr("Thickness (%s)", unit) : Lang.tr("Outline width (%s)", unit), stroke, 0.001f, 0f, 1000f, "%.3f")) {
			element.strokeWidth = Math.max(0, stroke[0]);
			changed = true;
		}
		if (!lineLike) {
			float[] strokeColor = argbToFloats(element.strokeColor);
			if (ImGui.colorEdit4(Lang.tr("Outline"), strokeColor)) {
				element.strokeColor = floatsToArgb(strokeColor);
				changed = true;
			}
		}
		if (element.shapeKind == ShapeKind.ROUNDED_RECTANGLE) {
			float[] radius = {element.cornerRadius};
			if (ImGui.dragFloat(Lang.tr("Corner radius (%s)", unit), radius, 0.002f, 0f, 1000f, "%.3f")) {
				element.cornerRadius = Math.max(0, radius[0]);
				changed = true;
			}
		}
		return changed;
	}

	private static void addShape(BillboardProject project, ShapeKind kind) {
		Element element = new Element();
		element.type = ElementType.SHAPE;
		element.shapeKind = kind;
		element.name = Lang.tr(kind.displayName) + " " + (project.elements.size() + 1);
		boolean lineLike = kind == ShapeKind.LINE || kind == ShapeKind.ARROW;
		element.width = 2;
		element.height = lineLike ? 0.5f : 1;
		element.strokeWidth = lineLike ? 0.1f : 0;
		element.cornerRadius = 0.2f;
		if (lineLike) {
			element.fillColor = 0xFFFFFFFF;
			element.fillColor2 = 0xFFFFFFFF;
		}
		place(element);
		add(project, element);
	}

	private static void addGroup(BillboardProject project) {
		Element element = new Element();
		element.type = ElementType.GROUP;
		element.name = Lang.tr("Group %s", project.elements.size() + 1);
		place(element);
		// New groups start at the top level even when another group is selected (nest them with "Move into group").
		Selection.set(null);
		add(project, element);
	}

	private static void setFont(BillboardProject project, UUID replayId, Element element, Path path) {
		if (!CustomFont.isReadable(path)) {
			Billboards.LOGGER.warn("Not a usable font file: {}", path);
			return;
		}
		try {
			element.fontAssetId = AssetStore.importFile(replayId, project, path);
			CustomFont.retry(element.fontAssetId);
			project.markDirty();
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to import {}", path, e);
		}
	}

	/** Text set up like a new vanilla text display: anchored at its bottom centre, lit by the world. */
	private static void addText(BillboardProject project) {
		Element element = new Element();
		element.type = ElementType.TEXT;
		element.name = Lang.tr("Text %s", project.elements.size() + 1);
		element.content.text = Lang.tr("Your text here");
		element.pivotX = 0.5f;
		element.pivotY = 1.0f;
		element.lighting = Lighting.WORLD;
		place(element);
		add(project, element);
	}

	private static void addPicture(BillboardProject project, UUID replayId, Path path, ElementType type) {
		Element element = new Element();
		element.type = type;
		element.mediaStartTick = Math.max(0, PlaybackTime.cursorTick());
		element.mediaLoop = type == ElementType.IMAGE;
		element.name = stripExtension(path.getFileName().toString());
		if (!importInto(project, replayId, element, path)) {
			return;
		}
		float aspect = aspect(path, type);
		element.width = 2.0f;
		element.height = 2.0f / aspect;
		place(element);
		add(project, element);
	}

	private static void replaceImage(BillboardProject project, UUID replayId, Element element, Path path) {
		if (importInto(project, replayId, element, path)) {
			element.height = element.width / aspect(path, element.type);
			project.markDirty();
		}
	}

	private static boolean importInto(BillboardProject project, UUID replayId, Element element, Path path) {
		try {
			element.assetId = AssetStore.importFile(replayId, project, path);
			ImageTextures.retry(element.assetId);
			MediaPlayers.retry(element.assetId);
			return true;
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to import {}", path, e);
			return false;
		}
	}

	private static void add(BillboardProject project, Element element) {
		// With a group selected, the new element goes into it, at the group's origin.
		Element selected = Selection.get(project);
		if (selected != null && selected.type == ElementType.GROUP) {
			element.parentId = selected.id;
			for (int component : new int[]{Channel.X, Channel.Y, Channel.Z, Channel.PITCH, Channel.YAW, Channel.ROLL}) {
				element.transform.set(component, 0);
			}
		}
		project.elements.add(element);
		project.markDirty();
		Selection.set(element.id);
	}

	/** 3 blocks in front of the camera, facing it. */
	private static void place(Element element) {
		ChannelValues transform = element.transform;
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		Vector3fc forward = camera.forwardVector();
		double distance = 3.0;
		double x = eye.x + forward.x() * distance;
		double y = eye.y + forward.y() * distance;
		double z = eye.z + forward.z() * distance;
		transform.set(Channel.X, x);
		transform.set(Channel.Y, y);
		transform.set(Channel.Z, z);

		// Point the element's front (+Z) back at the camera.
		double toCameraX = eye.x - x;
		double toCameraY = eye.y - y;
		double toCameraZ = eye.z - z;
		double horizontal = Math.sqrt(toCameraX * toCameraX + toCameraZ * toCameraZ);
		transform.set(Channel.YAW, Math.toDegrees(Math.atan2(toCameraX, toCameraZ)));
		transform.set(Channel.PITCH, -Math.toDegrees(Math.atan2(toCameraY, horizontal)));
	}

	private static void pickImage(UUID replayId, java.util.function.Consumer<Path> onPicked) {
		pickFile(replayId, Lang.tr("Images"), IMAGE_EXTENSIONS, onPicked);
	}

	private static void pickFile(UUID replayId, String description, String[] extensions, java.util.function.Consumer<Path> onPicked) {
		String start = FabricLoader.getInstance().getGameDir().toString();
		AsyncFileDialogs.openFileDialog(start, description, extensions).thenAccept(picked -> {
			if (picked != null) {
				// The dialog completes on another thread; touch the project on the render thread only.
				Minecraft.getInstance().execute(() -> {
					if (replayId.equals(ProjectManager.currentReplayId())) {
						onPicked.accept(Path.of(picked));
					}
				});
			}
		});
	}

	// ------------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------------

	/** Continues on the same line if a button with this label fits there, otherwise starts a new line. */
	private static void sameLineIfFits(String label) {
		float itemWidth = ImGui.calcTextSize(label).x + ImGui.getStyle().getFramePaddingX() * 2 + ImGui.getStyle().getItemSpacingX() + 24;
		float lineEnd = ImGui.getItemRectMaxX();
		float windowEnd = ImGui.getWindowPosX() + ImGui.getWindowContentRegionMaxX();
		if (lineEnd + itemWidth <= windowEnd) {
			ImGui.sameLine();
		}
	}

	private static float aspect(Path path, ElementType type) {
		if (type == ElementType.VIDEO || MediaPlayer.isAnimatedImage(path)) {
			try {
				MediaPlayer.Info info = MediaPlayer.probe(path);
				return info.width() > 0 && info.height() > 0 ? info.width() / (float) info.height() : 1.0f;
			} catch (Exception e) {
				return 16 / 9f;
			}
		}
		int[] size = ImageTextures.readSize(path);
		if (size == null || size[0] <= 0 || size[1] <= 0) {
			return 1.0f;
		}
		return size[0] / (float) size[1];
	}

	private static String stripExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	private static <T extends Enum<T>> T enumCombo(String label, T current, T[] values, java.util.function.Function<T, String> name) {
		T result = current;
		if (ImGui.beginCombo(label, Lang.tr(name.apply(current)))) {
			for (T value : values) {
				if (ImGui.selectable(Lang.tr(name.apply(value)), value == current)) {
					result = value;
				}
			}
			ImGui.endCombo();
		}
		return result;
	}

	private static float[] argbToFloats(int argb) {
		return new float[]{
			((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f
		};
	}

	private static int floatsToArgb(float[] rgba) {
		int r = Math.round(Math.max(0, Math.min(1, rgba[0])) * 255);
		int g = Math.round(Math.max(0, Math.min(1, rgba[1])) * 255);
		int b = Math.round(Math.max(0, Math.min(1, rgba[2])) * 255);
		int a = Math.round(Math.max(0, Math.min(1, rgba[3])) * 255);
		return a << 24 | r << 16 | g << 8 | b;
	}
}
