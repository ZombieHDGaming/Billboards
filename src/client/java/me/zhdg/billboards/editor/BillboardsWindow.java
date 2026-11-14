package me.zhdg.billboards.editor;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.utils.AsyncFileDialogs;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiCond;
import imgui.moulberry90.flag.ImGuiTreeNodeFlags;
import imgui.moulberry90.type.ImBoolean;
import imgui.moulberry90.type.ImString;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.keyframe.TrackEditor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.model.TextAlign;
import me.zhdg.billboards.project.AssetStore;
import me.zhdg.billboards.project.ProjectManager;
import me.zhdg.billboards.render.ImageTextures;
import me.zhdg.billboards.render.OverlayRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.nio.file.Path;
import java.util.UUID;

/**
 * The Billboards editor window: an outliner of the replay's overlays and an inspector for the selected one.
 * Rendered inside Flashback's ImGui frame (see {@code ReplayUIMixin}).
 */
public final class BillboardsWindow {

	public static final String WINDOW_ID = "billboards";
	private static final String[] IMAGE_EXTENSIONS = {"png", "jpg", "jpeg", "gif", "bmp"};

	private static final ImBoolean OPEN = new ImBoolean(true);
	private static @Nullable UUID selected = null;
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

		ImGui.setNextWindowSize(380, 560, ImGuiCond.FirstUseEver);
		OPEN.set(true);
		if (ImGui.begin("Billboards###billboards", OPEN)) {
			try {
				renderAddMenu(project, replayId);
				ImGui.separator();
				renderOutliner(project, editorState);
				ImGui.separator();
				Element element = selected == null ? null : project.get(selected);
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
		ImGui.sameLine();
		if (ImGui.button("Add text")) {
			ImGui.openPopup("##billboards_add_text");
		}

		if (ImGui.beginPopup("##billboards_add_image")) {
			if (ImGui.menuItem("In the world")) {
				pickImage(replayId, path -> addImage(project, replayId, path, Space.WORLD));
			}
			if (ImGui.menuItem("On screen")) {
				pickImage(replayId, path -> addImage(project, replayId, path, Space.SCREEN));
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
	}

	private static void renderOutliner(BillboardProject project, EditorState editorState) {
		if (project.elements.isEmpty()) {
			ImGui.textDisabled("No overlays yet. Add an image or some text.");
			return;
		}

		ImGui.beginChild("##billboards_outliner", 0, 150, true);
		Element toDelete = null;
		Element toDuplicate = null;
		int moveFrom = -1;
		int moveTo = -1;

		for (int i = 0; i < project.elements.size(); i++) {
			Element element = project.elements.get(i);
			ImGui.pushID(element.id.toString());

			if (ImGui.checkbox("##visible", !element.hidden)) {
				element.hidden = !element.hidden;
				project.markDirty();
			}
			ImGuiHelper.tooltip("Show or hide this overlay (also in exports)");
			ImGui.sameLine();

			String label = element.name + "  (" + element.type.displayName + ", " + element.space.displayName + ")";
			if (ImGui.selectable(label, element.id.equals(selected))) {
				selected = element.id;
			}
			if (ImGui.beginPopupContextItem("##context")) {
				if (ImGui.menuItem("Duplicate")) {
					toDuplicate = element;
				}
				if (ImGui.menuItem("Move up", "", false, i > 0)) {
					moveFrom = i;
					moveTo = i - 1;
				}
				if (ImGui.menuItem("Move down", "", false, i < project.elements.size() - 1)) {
					moveFrom = i;
					moveTo = i + 1;
				}
				ImGui.separator();
				if (ImGui.menuItem("Delete")) {
					toDelete = element;
				}
				ImGui.endPopup();
			}
			ImGui.popID();
		}
		ImGui.endChild();

		if (toDuplicate != null) {
			Element copy = toDuplicate.copy();
			copy.name = toDuplicate.name + " copy";
			project.elements.add(project.elements.indexOf(toDuplicate) + 1, copy);
			TrackEditor.duplicateTracks(editorState, toDuplicate.id, copy);
			selected = copy.id;
			project.markDirty();
		}
		if (moveFrom >= 0) {
			project.elements.add(moveTo, project.elements.remove(moveFrom));
			project.markDirty();
		}
		if (toDelete != null) {
			project.elements.remove(toDelete);
			TrackEditor.removeTracks(editorState, toDelete.id);
			if (toDelete.id.equals(selected)) {
				selected = null;
			}
			project.markDirty();
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
			renderChannel(project, editorState, element, channel, frame, tick);
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
		}

		float[] pivot = {element.pivotX, element.pivotY};
		if (ImGui.dragFloat2("Pivot (0-1)", pivot, 0.005f, 0f, 1f, "%.3f")) {
			element.pivotX = pivot[0];
			element.pivotY = pivot[1];
			changed = true;
		}
		ImGuiHelper.tooltip("The point of the overlay that sits on its position and that it rotates around. 0.5, 0.5 is the centre");

		int[] layer = {element.layer};
		if (ImGuiHelper.inputInt("Layer", layer)) {
			element.layer = layer[0];
			changed = true;
		}
		ImGuiHelper.tooltip(element.space == Space.SCREEN
			? "Higher layers are drawn on top"
			: "Only affects overlays that are not hidden behind blocks; the rest are sorted by distance");

		String unit = element.space == Space.WORLD ? "blocks" : "of frame height";
		if (element.type == ElementType.IMAGE) {
			String fileName = element.assetId == null ? "(none)" : project.assets.getOrDefault(element.assetId, element.assetId);
			ImGui.text("Image: " + fileName);
			ImGui.sameLine();
			if (ImGui.smallButton("Replace...")) {
				pickImage(replayId, path -> replaceImage(project, replayId, element, path));
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
		} else {
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
				ImGui.textDisabled("Not animated: edits change the base value");
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
			if (state == TrackEditor.KeyState.NOT_ANIMATED) {
				element.setBase(channel, shown);
				project.markDirty();
			} else {
				TrackEditor.setKeyframe(editorState, element, shown, tick);
			}
		}
		ImGui.popID();
	}

	// ------------------------------------------------------------------------------------------------
	// Creating elements
	// ------------------------------------------------------------------------------------------------

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

	private static void addImage(BillboardProject project, UUID replayId, Path path, Space space) {
		Element element = new Element();
		element.type = ElementType.IMAGE;
		element.space = space;
		element.name = stripExtension(path.getFileName().toString());
		if (!importInto(project, replayId, element, path)) {
			return;
		}
		float aspect = aspect(path);
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
			element.height = element.width / aspect(path);
			project.markDirty();
		}
	}

	private static boolean importInto(BillboardProject project, UUID replayId, Element element, Path path) {
		try {
			element.assetId = AssetStore.importFile(replayId, project, path);
			ImageTextures.retry(element.assetId);
			return true;
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to import {}", path, e);
			return false;
		}
	}

	private static void add(BillboardProject project, Element element) {
		project.elements.add(element);
		project.markDirty();
		selected = element.id;
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
		String start = FabricLoader.getInstance().getGameDir().toString();
		AsyncFileDialogs.openFileDialog(start, "Images", IMAGE_EXTENSIONS).thenAccept(picked -> {
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

	private static float aspect(Path path) {
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
