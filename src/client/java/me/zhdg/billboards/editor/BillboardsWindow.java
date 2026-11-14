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
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.keyframe.TrackEditor;
import me.zhdg.billboards.model.AttachAnchor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Gradient;
import me.zhdg.billboards.model.ShapeKind;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.model.TextAlign;
import me.zhdg.billboards.project.AssetStore;
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
		if (ImGui.begin("Billboards###billboards", OPEN)) {
			try {
				renderAddMenu(project, replayId);
				renderViewportToolbar();
				ImGui.separator();
				renderOutliner(project, editorState);
				ImGui.separator();
				Element element = Selection.get(project);
				if (element == null) {
					ImGui.textDisabled("Select an overlay to edit it.");
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
		if (ImGui.button("Add image...")) {
			ImGui.openPopup("##billboards_add_image");
		}
		sameLineIfFits("Add video...");
		if (ImGui.button("Add video...")) {
			ImGui.openPopup("##billboards_add_video");
		}
		ImGuiHelper.tooltip("A video file (MP4, MOV, MKV, WebM...) that plays in sync with the replay. Animated GIFs and PNGs can be added as images.");
		sameLineIfFits("Add text");
		if (ImGui.button("Add text")) {
			ImGui.openPopup("##billboards_add_text");
		}
		sameLineIfFits("Add shape");
		if (ImGui.button("Add shape")) {
			ImGui.openPopup("##billboards_add_shape");
		}
		sameLineIfFits("Add group");
		if (ImGui.button("Add group")) {
			ImGui.openPopup("##billboards_add_group");
		}
		ImGuiHelper.tooltip("Groups move, rotate, scale and fade everything inside them together. Add things to a group by selecting it first, or with \"Move into group\" in the list.");

		if (ImGui.beginPopup("##billboards_add_image")) {
			if (ImGui.menuItem("In the world")) {
				pickImage(replayId, path -> addPicture(project, replayId, path, Space.WORLD, ElementType.IMAGE));
			}
			if (ImGui.menuItem("On screen")) {
				pickImage(replayId, path -> addPicture(project, replayId, path, Space.SCREEN, ElementType.IMAGE));
			}
			ImGui.endPopup();
		}
		if (ImGui.beginPopup("##billboards_add_video")) {
			if (ImGui.menuItem("In the world")) {
				pickFile(replayId, "Videos", VIDEO_EXTENSIONS, path -> addPicture(project, replayId, path, Space.WORLD, ElementType.VIDEO));
			}
			if (ImGui.menuItem("On screen")) {
				pickFile(replayId, "Videos", VIDEO_EXTENSIONS, path -> addPicture(project, replayId, path, Space.SCREEN, ElementType.VIDEO));
			}
			ImGui.endPopup();
		}
		if (ImGui.beginPopup("##billboards_add_text")) {
			if (ImGui.menuItem("In the world")) {
				addText(project, Space.WORLD);
			}
			if (ImGui.menuItem("On screen")) {
				addText(project, Space.SCREEN);
			}
			ImGui.endPopup();
		}
		if (ImGui.beginPopup("##billboards_add_shape")) {
			for (ShapeKind kind : ShapeKind.values()) {
				if (ImGui.beginMenu(kind.displayName)) {
					if (ImGui.menuItem("In the world")) {
						addShape(project, kind, Space.WORLD);
					}
					if (ImGui.menuItem("On screen")) {
						addShape(project, kind, Space.SCREEN);
					}
					ImGui.endMenu();
				}
			}
			ImGui.endPopup();
		}
		if (ImGui.beginPopup("##billboards_add_group")) {
			if (ImGui.menuItem("In the world")) {
				addGroup(project, Space.WORLD);
			}
			if (ImGui.menuItem("On screen")) {
				addGroup(project, Space.SCREEN);
			}
			ImGui.endPopup();
		}
	}

	private static void renderViewportToolbar() {
		ImGui.textDisabled("Viewport:");
		for (ViewportTools.Tool tool : ViewportTools.Tool.values()) {
			String label = tool.displayName + " (" + tool.key + ")";
			sameLineIfFits(label);
			if (ImGui.radioButton(label, ViewportTools.tool == tool)) {
				ViewportTools.tool = tool;
			}
		}
		if (ImGui.checkbox("Local axes", ViewportTools.localAxes)) {
			ViewportTools.localAxes = !ViewportTools.localAxes;
		}
		ImGuiHelper.tooltip("Move and rotate along the overlay's own axes instead of the world's");
		ImGui.sameLine();
		if (ImGui.checkbox("Snap", ViewportTools.snap)) {
			ViewportTools.snap = !ViewportTools.snap;
		}
		ImGuiHelper.tooltip("Move in 0.25 block steps (1% of the frame on screen), rotate in 15° steps, scale in 0.1 steps");
		ImGui.sameLine();
		if (ImGui.checkbox("Auto-key", ViewportTools.autoKey)) {
			ViewportTools.autoKey = !ViewportTools.autoKey;
		}
		ImGuiHelper.tooltip("When on, editing a channel that isn't animated yet adds a keyframe at the cursor instead of changing its base value");
		ImGui.textDisabled("Click an overlay in the viewport to select it; drag the gizmo to edit it.");
	}

	private static void renderOutliner(BillboardProject project, EditorState editorState) {
		if (project.elements.isEmpty()) {
			ImGui.textDisabled("No overlays yet. Add an image, text, a shape or a group.");
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
			ImGuiHelper.tooltip(element.type == ElementType.GROUP ? "Show or hide this group and everything in it" : "Show or hide this overlay (also in exports)");
			ImGui.sameLine();

			String label = (element.type == ElementType.GROUP ? "[+] " : "") + element.name + "  (" + element.type.displayName + ", " + element.space.displayName + ")";
			if (ImGui.selectable(label, Selection.is(element.id))) {
				Selection.set(element.id);
			}
			if (depth > 0) {
				ImGui.unindent(depth * 14f);
			}

			if (ImGui.beginPopupContextItem("##context")) {
				Element previous = i > 0 ? siblings.get(i - 1) : null;
				Element next = i < siblings.size() - 1 ? siblings.get(i + 1) : null;
				if (ImGui.menuItem("Duplicate")) {
					action[0] = () -> Selection.set(duplicateTree(project, editorState, element, element.parentId).id);
				}
				if (ImGui.menuItem("Move up", "", false, previous != null)) {
					action[0] = () -> swap(project, element, previous);
				}
				if (ImGui.menuItem("Move down", "", false, next != null)) {
					action[0] = () -> swap(project, element, next);
				}
				List<Element> groups = project.elements.stream()
					.filter(g -> g.type == ElementType.GROUP && g.space == element.space && !project.isWithin(g, element.id) && !g.id.equals(element.parentId))
					.toList();
				if (ImGui.beginMenu("Move into group", !groups.isEmpty())) {
					for (Element group : groups) {
						if (ImGui.menuItem(group.name + "##" + group.id)) {
							action[0] = () -> reparent(project, editorState, element, group);
						}
					}
					ImGui.endMenu();
				}
				if (element.parentId != null && ImGui.menuItem("Move out of group")) {
					Element group = project.get(element.parentId);
					Element outer = group == null || group.parentId == null ? null : project.get(group.parentId);
					action[0] = () -> reparent(project, editorState, element, outer);
				}
				if (element.type == ElementType.GROUP && ImGui.menuItem("Ungroup")) {
					action[0] = () -> ungroup(project, editorState, element);
				}
				ImGui.separator();
				if (ImGui.menuItem(element.type == ElementType.GROUP ? "Delete group and contents" : "Delete")) {
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

	/** Swaps two siblings' places in the project's list (which is also their order on screen within a layer). */
	private static void swap(BillboardProject project, Element a, Element b) {
		int indexA = project.elements.indexOf(a);
		int indexB = project.elements.indexOf(b);
		project.elements.set(indexA, b);
		project.elements.set(indexB, a);
	}

	/** Moves an element into a group (or to the top level), keeping it where it is on screen / in the world. */
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
		for (Element element : List.copyOf(project.elements)) {
			if (project.isWithin(element, root.id)) {
				project.elements.remove(element);
				TrackEditor.removeTracks(editorState, element.id);
				if (Selection.is(element.id)) {
					Selection.set(null);
				}
			}
		}
	}

	/** Copies an element (and, for a group, everything in it) with its keyframe tracks. Returns the copy. */
	private static Element duplicateTree(BillboardProject project, EditorState editorState, Element source, @Nullable UUID parentId) {
		Element copy = source.copy();
		copy.parentId = parentId;
		if (Objects.equals(parentId, source.parentId)) {
			copy.name = source.name + " copy";
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
	// Inspector
	// ------------------------------------------------------------------------------------------------

	private static void renderInspector(BillboardProject project, UUID replayId, EditorState editorState, Element element) {
		ImGui.pushID(element.id.toString());
		ImGui.pushItemWidth(220);

		if (!element.id.equals(nameBufferOwner)) {
			nameBufferOwner = element.id;
			nameBuffer = ImGuiHelper.createResizableImString(element.name);
		}
		if (ImGui.inputText("Name", nameBuffer)) {
			element.name = ImGuiHelper.getString(nameBuffer);
			project.markDirty();
		}
		if (ImGui.isItemDeactivatedAfterEdit()) {
			TrackEditor.renameTracks(editorState, element);
		}

		if (ImGui.collapsingHeader("Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
			if (renderSettings(project, replayId, element)) {
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

		if (element.space == Space.WORLD) {
			Facing facing = enumCombo("Facing", element.facing, Facing.values(), f -> f.displayName);
			if (facing != element.facing) {
				element.facing = facing;
				changed = true;
			}
			if (ImGui.checkbox("Hidden behind blocks", element.depthTest)) {
				element.depthTest = !element.depthTest;
				changed = true;
			}
			ImGuiHelper.tooltip("When off, the overlay is always drawn on top of the world");
			if (element.parentId == null && renderAttachment(element)) {
				changed = true;
			}
		}

		if (element.type != ElementType.GROUP) {
			float[] pivot = {element.pivotX, element.pivotY};
			if (ImGui.dragFloat2("Pivot (0-1)", pivot, 0.005f, 0f, 1f, "%.3f")) {
				element.pivotX = pivot[0];
				element.pivotY = pivot[1];
				changed = true;
			}
			ImGuiHelper.tooltip("The point of the overlay that sits on its position and that it rotates around. 0.5, 0.5 is the centre");
		} else {
			ImGui.textDisabled("Position, rotation, scale, opacity and tint apply to everything in the group.");
		}

		if (element.parentId == null) {
			int[] layer = {element.layer};
			if (ImGuiHelper.inputInt("Layer", layer)) {
				element.layer = layer[0];
				changed = true;
			}
			ImGuiHelper.tooltip(element.space == Space.SCREEN
				? "Higher layers are drawn on top (a group's contents are drawn in list order)"
				: "Only affects overlays that are not hidden behind blocks; the rest are sorted by distance");
		} else {
			ImGui.textDisabled("Inside a group: position is relative to the group.");
		}

		String unit = element.space == Space.WORLD ? "blocks" : "of frame height";
		if (element.type.isPicture()) {
			boolean video = element.type == ElementType.VIDEO;
			String fileName = element.assetId == null ? "(none)" : project.assets.getOrDefault(element.assetId, element.assetId);
			ImGui.text((video ? "Video: " : "Image: ") + fileName);
			ImGui.sameLine();
			if (ImGui.smallButton("Replace...")) {
				pickFile(replayId, video ? "Videos" : "Images", video ? VIDEO_EXTENSIONS : IMAGE_EXTENSIONS,
					path -> replaceImage(project, replayId, element, path));
			}
			float[] size = {element.width, element.height};
			if (ImGui.dragFloat2("Size (" + unit + ")", size, 0.01f, 0.001f, 10000f, "%.3f")) {
				element.width = size[0];
				element.height = size[1];
				changed = true;
			}
			if (ImGui.checkbox("Pixelated", element.pixelated)) {
				element.pixelated = !element.pixelated;
				changed = true;
			}
			ImGuiHelper.tooltip("Use nearest-neighbour filtering, for pixel art");
			if (MediaPlayers.isPlayed(replayId, element)) {
				changed |= renderPlaybackSettings(replayId, element);
			}
		} else if (element.type == ElementType.SHAPE) {
			changed |= renderShapeSettings(element, unit);
		} else if (element.type == ElementType.TEXT) {
			String fontName = element.fontAssetId == null ? "Minecraft" : project.assets.getOrDefault(element.fontAssetId, element.fontAssetId);
			ImGui.text("Font: " + fontName);
			ImGui.sameLine();
			if (ImGui.smallButton("Choose...")) {
				pickFile(replayId, "Fonts", FONT_EXTENSIONS, path -> setFont(project, replayId, element, path));
			}
			ImGuiHelper.tooltip("Use a TTF or OTF font file instead of Minecraft's font");
			if (element.fontAssetId != null) {
				ImGui.sameLine();
				if (ImGui.smallButton("Minecraft font")) {
					element.fontAssetId = null;
					changed = true;
				}
			}
			float[] fontSize = {element.fontSize};
			if (ImGui.dragFloat("Line height (" + unit + ")", fontSize, 0.002f, 0.001f, 1000f, "%.3f")) {
				element.fontSize = fontSize[0];
				changed = true;
			}
			TextAlign align = enumCombo("Align", element.align, TextAlign.values(), a -> a.displayName);
			if (align != element.align) {
				element.align = align;
				changed = true;
			}
			if (ImGui.checkbox("Shadow", element.textShadow)) {
				element.textShadow = !element.textShadow;
				changed = true;
			}
			float[] background = argbToFloats(element.backgroundColor);
			if (ImGui.colorEdit4("Background", background)) {
				element.backgroundColor = floatsToArgb(background);
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
			if (ImGui.button("Attach to entity...")) {
				ImGui.openPopup("##billboards_attach");
			}
			ImGuiHelper.tooltip(positionAnimated
				? "Follow an entity. Position keyframes will then be offsets from the entity."
				: "Follow an entity, e.g. to pin a label above a player's head. The position becomes an offset from the entity.");
			if (ImGui.beginPopup("##billboards_attach")) {
				List<Entity> candidates = attachCandidates();
				if (candidates.isEmpty()) {
					ImGui.textDisabled("No entities nearby");
				}
				Vec3 eye = Minecraft.getInstance().gameRenderer.mainCamera().position();
				for (Entity entity : candidates) {
					String label = entity.getName().getString() + "  (" + entity.getType().getDescription().getString() + ", "
						+ Math.round(entity.position().distanceTo(eye)) + " blocks)##" + entity.getUUID();
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
		ImGui.text("Attached to " + name + (entity == null ? " (not loaded at this tick)" : ""));
		ImGui.sameLine();
		if (ImGui.smallButton("Detach")) {
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
			? "Position keyframes stay as they are and become world coordinates again"
			: "Stop following the entity, leaving the overlay where it is now");

		AttachAnchor anchor = enumCombo("Anchor", element.attachAnchor, AttachAnchor.values(), a -> a.displayName);
		if (anchor != element.attachAnchor) {
			element.attachAnchor = anchor;
			changed = true;
		}
		if (ImGui.checkbox("Turn with entity", element.attachRotate)) {
			element.attachRotate = !element.attachRotate;
			changed = true;
		}
		ImGuiHelper.tooltip("Rotate the offset (and a fixed-facing overlay) with the entity's body");
		ImGui.textDisabled("Position is an offset from the anchor while attached.");
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
		if (!ImGui.collapsingHeader(channel.displayName, ImGuiTreeNodeFlags.DefaultOpen)) {
			return;
		}
		ImGui.pushID(channel.id());

		TrackEditor.KeyState state = tick < 0 ? TrackEditor.KeyState.NOT_ANIMATED : TrackEditor.keyState(editorState, element.id, channel, tick);
		switch (state) {
			case NOT_ANIMATED -> {
				if (ImGui.smallButton("Animate")) {
					TrackEditor.setKeyframe(editorState, element, element.base(channel), tick);
				}
				ImGuiHelper.tooltip("Add a keyframe at the cursor (tick " + tick + ") and show this channel on the timeline");
				ImGui.sameLine();
				ImGui.textDisabled(ViewportTools.autoKey
					? "Not animated: auto-key adds a keyframe at tick " + tick
					: "Not animated: edits change the base value");
			}
			case ANIMATED -> {
				if (ImGui.smallButton("Add keyframe")) {
					TrackEditor.setKeyframe(editorState, element, OverlayRenderer.valuesOf(frame, element, channel), tick);
				}
				ImGui.sameLine();
				ImGui.textDisabled("Animated: edits add a keyframe at tick " + tick);
			}
			case ON_KEYFRAME -> {
				if (ImGui.smallButton("Remove keyframe")) {
					TrackEditor.removeKeyframe(editorState, element.id, channel, tick);
				}
				ImGui.sameLine();
				ImGui.textDisabled("Editing the keyframe at tick " + tick);
			}
		}

		ChannelValues shown = (state == TrackEditor.KeyState.NOT_ANIMATED ? element.base(channel) : OverlayRenderer.valuesOf(frame, element, channel)).copy();
		boolean changed = ChannelEditor.render(shown, element.space, shown::set, text -> shown.text = text);
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
			ImGui.textDisabled("Can't be played; see the log");
		}
		int[] start = {element.mediaStartTick};
		if (ImGuiHelper.inputInt("Starts at tick", start)) {
			element.mediaStartTick = Math.max(0, start[0]);
			changed = true;
		}
		ImGuiHelper.tooltip("Replay tick at which it starts playing; before that it shows its first frame");
		ImGui.sameLine();
		if (ImGui.smallButton("Cursor")) {
			element.mediaStartTick = Math.max(0, PlaybackTime.cursorTick());
			changed = true;
		}
		ImGuiHelper.tooltip("Start playing at the timeline cursor");
		float[] speed = {element.mediaSpeed};
		if (ImGui.dragFloat("Speed", speed, 0.01f, 0.01f, 100f, "%.2fx")) {
			element.mediaSpeed = Math.max(0.01f, speed[0]);
			changed = true;
		}
		if (ImGui.checkbox("Loop", element.mediaLoop)) {
			element.mediaLoop = !element.mediaLoop;
			changed = true;
		}
		ImGuiHelper.tooltip("Start again after the end instead of holding the last frame");
		return changed;
	}

	private static boolean renderShapeSettings(Element element, String unit) {
		boolean changed = false;
		ShapeKind kind = enumCombo("Shape", element.shapeKind, ShapeKind.values(), k -> k.displayName);
		if (kind != element.shapeKind) {
			element.shapeKind = kind;
			changed = true;
		}
		float[] size = {element.width, element.height};
		if (ImGui.dragFloat2("Size (" + unit + ")", size, 0.005f, 0.001f, 10000f, "%.3f")) {
			element.width = size[0];
			element.height = size[1];
			changed = true;
		}
		float[] fill = argbToFloats(element.fillColor);
		if (ImGui.colorEdit4(element.gradient == Gradient.NONE ? "Fill" : "Fill (start)", fill)) {
			element.fillColor = floatsToArgb(fill);
			changed = true;
		}
		Gradient gradient = enumCombo("Gradient", element.gradient, Gradient.values(), g -> g.displayName);
		if (gradient != element.gradient) {
			element.gradient = gradient;
			changed = true;
		}
		if (element.gradient != Gradient.NONE) {
			float[] fill2 = argbToFloats(element.fillColor2);
			if (ImGui.colorEdit4("Fill (end)", fill2)) {
				element.fillColor2 = floatsToArgb(fill2);
				changed = true;
			}
		}
		boolean lineLike = element.shapeKind == ShapeKind.LINE || element.shapeKind == ShapeKind.ARROW;
		float[] stroke = {element.strokeWidth};
		if (ImGui.dragFloat(lineLike ? "Thickness (" + unit + ")" : "Outline width (" + unit + ")", stroke, 0.001f, 0f, 1000f, "%.3f")) {
			element.strokeWidth = Math.max(0, stroke[0]);
			changed = true;
		}
		if (!lineLike) {
			float[] strokeColor = argbToFloats(element.strokeColor);
			if (ImGui.colorEdit4("Outline", strokeColor)) {
				element.strokeColor = floatsToArgb(strokeColor);
				changed = true;
			}
		}
		if (element.shapeKind == ShapeKind.ROUNDED_RECTANGLE) {
			float[] radius = {element.cornerRadius};
			if (ImGui.dragFloat("Corner radius (" + unit + ")", radius, 0.002f, 0f, 1000f, "%.3f")) {
				element.cornerRadius = Math.max(0, radius[0]);
				changed = true;
			}
		}
		return changed;
	}

	private static void addShape(BillboardProject project, ShapeKind kind, Space space) {
		Element element = new Element();
		element.type = ElementType.SHAPE;
		element.space = space;
		element.shapeKind = kind;
		element.name = kind.displayName + " " + (project.elements.size() + 1);
		boolean lineLike = kind == ShapeKind.LINE || kind == ShapeKind.ARROW;
		if (space == Space.WORLD) {
			element.width = 2;
			element.height = lineLike ? 0.5f : 1;
			element.strokeWidth = lineLike ? 0.1f : 0;
			element.cornerRadius = 0.2f;
		} else {
			element.width = 0.6f;
			element.height = lineLike ? 0.08f : 0.15f;
			element.strokeWidth = lineLike ? 0.015f : 0;
			element.cornerRadius = 0.03f;
		}
		if (lineLike) {
			element.fillColor = 0xFFFFFFFF;
			element.fillColor2 = 0xFFFFFFFF;
		}
		place(element);
		add(project, element);
	}

	private static void addGroup(BillboardProject project, Space space) {
		Element element = new Element();
		element.type = ElementType.GROUP;
		element.space = space;
		element.name = "Group " + (project.elements.size() + 1);
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

	private static void addText(BillboardProject project, Space space) {
		Element element = new Element();
		element.type = ElementType.TEXT;
		element.space = space;
		element.name = "Text " + (project.elements.size() + 1);
		element.content.text = "Your text here";
		element.fontSize = space == Space.WORLD ? 0.25f : 0.06f;
		place(element);
		add(project, element);
	}

	private static void addPicture(BillboardProject project, UUID replayId, Path path, Space space, ElementType type) {
		Element element = new Element();
		element.type = type;
		element.mediaStartTick = Math.max(0, PlaybackTime.cursorTick());
		element.mediaLoop = type == ElementType.IMAGE;
		element.space = space;
		element.name = stripExtension(path.getFileName().toString());
		if (!importInto(project, replayId, element, path)) {
			return;
		}
		float aspect = aspect(path, type);
		if (space == Space.WORLD) {
			element.width = 2.0f;
			element.height = 2.0f / aspect;
		} else {
			element.height = 0.25f;
			element.width = 0.25f * aspect;
		}
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
		// With a group of the same space selected, the new element goes into it, at the group's origin.
		Element selected = Selection.get(project);
		if (selected != null && selected.type == ElementType.GROUP && selected.space == element.space) {
			element.parentId = selected.id;
			for (int component : new int[]{Channel.X, Channel.Y, Channel.Z, Channel.PITCH, Channel.YAW, Channel.ROLL}) {
				element.transform.set(component, 0);
			}
		}
		project.elements.add(element);
		project.markDirty();
		Selection.set(element.id);
	}

	/** World: 3 blocks in front of the camera, facing it. Screen: the centre of the frame. */
	private static void place(Element element) {
		ChannelValues transform = element.transform;
		if (element.space == Space.SCREEN) {
			transform.set(Channel.X, 0.5);
			transform.set(Channel.Y, 0.5);
			return;
		}

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
		pickFile(replayId, "Images", IMAGE_EXTENSIONS, onPicked);
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
		if (ImGui.beginCombo(label, name.apply(current))) {
			for (T value : values) {
				if (ImGui.selectable(name.apply(value), value == current)) {
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
