package me.zhdg.billboards.editor;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.state.EditorState;
import imgui.moulberry90.ImDrawList;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.extension.imguizmo.ImGuizmo;
import imgui.moulberry90.extension.imguizmo.flag.Mode;
import imgui.moulberry90.extension.imguizmo.flag.Operation;
import imgui.moulberry90.flag.ImGuiKey;
import imgui.moulberry90.flag.ImGuiMouseButton;
import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.keyframe.TrackEditor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.project.ProjectManager;
import me.zhdg.billboards.render.ElementGeometry;
import me.zhdg.billboards.render.OverlayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.UUID;

/**
 * Viewport interaction for overlays: clicking to select, the selection outline, and ImGuizmo move/rotate/scale
 * gizmos. Runs inside Flashback's game-frame window (see {@code ReplayUIMixin}) so ImGuizmo sees the frame as hovered.
 */
public final class ViewportTools {

	public enum Tool {
		MOVE("Move", "W"),
		ROTATE("Rotate", "E"),
		SCALE("Scale", "R");

		public final String displayName;
		public final String key;

		Tool(String displayName, String key) {
			this.displayName = displayName;
			this.key = key;
		}
	}

	private static final int OUTLINE_COLOUR = 0xFF2BC4FF; // ABGR: orange
	private static final float SNAP_WORLD_MOVE = 0.25f;
	private static final float SNAP_ROTATE = 15f;
	private static final float SNAP_SCALE = 0.1f;
	/** Screen-space moves snap to 1% of the frame height. */
	private static final float SNAP_SCREEN_MOVE_FRACTION = 0.01f;

	public static Tool tool = Tool.MOVE;
	/** Gizmo axes follow the element's rotation (true) or the world axes (false). */
	public static boolean localAxes = true;
	public static boolean snap = false;
	/** Edits on channels that aren't animated yet create keyframes instead of changing the base value. */
	public static boolean autoKey = false;

	/** The transform being dragged, shown live instead of the stored value until the drag ends. */
	private record Drag(UUID elementId, ChannelValues values) {
	}

	private static @Nullable Drag drag = null;
	private static boolean consumedClick = false;
	/** ImGuizmo state from this frame. Kept here because ImGuizmo must only be called inside the ImGui frame. */
	private static boolean gizmoHovered = false;
	private static boolean gizmoActive = false;

	static {
		OverlayRenderer.setPreviewSource((elementId, channel) ->
			drag != null && channel == Channel.TRANSFORM && drag.elementId().equals(elementId) ? drag.values() : null);
	}

	private ViewportTools() {
	}

	/** True when this frame's viewport click belongs to Billboards, so Flashback shouldn't grab the mouse for it. */
	public static boolean ownsMouse() {
		return consumedClick || drag != null || gizmoActive || gizmoHovered;
	}

	/** Whether the mouse is over the selected overlay's gizmo (as of the last frame). */
	public static boolean isGizmoHovered() {
		return gizmoHovered;
	}

	/** Whether a gizmo drag is in progress. */
	public static boolean isDragging() {
		return drag != null;
	}

	// ------------------------------------------------------------------------------------------------

	/** Called every frame inside Flashback's game-frame window. */
	public static void renderInFrame() {
		consumedClick = false;
		gizmoHovered = false;
		gizmoActive = false;
		if (!BillboardsWindow.isOpen() || Flashback.isExporting()) {
			cancelDrag();
			return;
		}
		BillboardProject project = ProjectManager.getCurrent();
		EditorState editorState = PlaybackTime.liveEditorState();
		OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
		if (project == null || editorState == null || camera == null) {
			cancelDrag();
			return;
		}

		float rectX = ImGui.getMainViewport().getPosX() + ReplayUI.frameX;
		float rectY = ImGui.getMainViewport().getPosY() + ReplayUI.frameY;
		float rectW = ReplayUI.frameWidth;
		float rectH = ReplayUI.frameHeight;
		boolean hovered = ImGui.isWindowHovered() && !ReplayUI.isMovingCamera();

		if (hovered && !ReplayUI.getIO().getWantTextInput()) {
			if (ImGui.isKeyPressed(ImGuiKey.W, false)) tool = Tool.MOVE;
			if (ImGui.isKeyPressed(ImGuiKey.E, false)) tool = Tool.ROTATE;
			if (ImGui.isKeyPressed(ImGuiKey.R, false)) tool = Tool.SCALE;
		}

		ImGuizmo.beginFrame();
		ImGuizmo.setDrawList();
		ImGuizmo.setRect(rectX, rectY, rectW, rectH);

		Element selected = Selection.get(project);
		if (selected == null || selected.hidden) {
			cancelDrag();
		} else {
			KeyframeEvaluator.Frame frame = KeyframeEvaluator.evaluate(PlaybackTime.current());
			ChannelValues transform = OverlayRenderer.valuesOf(frame, selected, Channel.TRANSFORM);
			ChannelValues content = OverlayRenderer.valuesOf(frame, selected, Channel.CONTENT);
			drawOutline(selected, transform, content, camera, rectX, rectY, rectW, rectH);
			manipulate(project, editorState, selected, transform, camera, rectW, rectH);
		}

		if (selected != null && !selected.hidden) {
			gizmoHovered = ImGuizmo.isOver();
			gizmoActive = ImGuizmo.isUsing();
		}

		// Click to select. A click on empty space keeps Flashback's behaviour (grabbing the mouse to look around).
		if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left) && !gizmoActive && !gizmoHovered) {
			Vec2 fraction = ReplayUI.getMouseViewportFraction();
			// ReplayUI.getMouseLookVector() needs Flashback's "frame hovered" flag, which it only sets later in the
			// frame, so build the ray from the parts that don't.
			Vec3 forwards = ReplayUI.getForwardsVector(ReplayUI.getIO().getMousePosX(), ReplayUI.getIO().getMousePosY());
			Vec3 direction = forwards == null ? null : ReplayUI.getMouseLookVectorFromForwards(forwards);
			UUID hit = ViewportPicker.pick(fraction.x, fraction.y, direction);
			if (hit != null) {
				Selection.set(hit);
				consumedClick = true;
			}
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Gizmo
	// ------------------------------------------------------------------------------------------------

	private static void manipulate(BillboardProject project, EditorState editorState, Element element, ChannelValues current,
	                               OverlayRenderer.SceneCamera camera, float rectW, float rectH) {
		ChannelValues start = drag != null && drag.elementId().equals(element.id) ? drag.values() : current;

		float[] view;
		float[] projection;
		Matrix4f model;
		int operation;
		boolean screen = element.space == Space.SCREEN;
		if (screen) {
			view = new Matrix4f().get(new float[16]);
			projection = new Matrix4f().setOrtho(0, rectW, 0, rectH, -1, 1).get(new float[16]);
			model = screenGizmoModel(start, rectW, rectH);
			operation = switch (tool) {
				case MOVE -> Operation.TRANSLATE_X | Operation.TRANSLATE_Y;
				case ROTATE -> Operation.ROTATE_Z;
				case SCALE -> Operation.SCALE_X | Operation.SCALE_Y;
			};
		} else {
			model = ElementGeometry.worldModel(element, start, camera.position(), camera.orientation());
			if (model == null) {
				cancelDrag();
				return; // attached to an entity that isn't loaded
			}
			view = camera.viewRotation().get(new float[16]);
			projection = gizmoProjection(camera.projection()).get(new float[16]);
			operation = switch (tool) {
				case MOVE -> Operation.TRANSLATE;
				// Camera-facing elements get their orientation from the camera, so only roll is theirs to change.
				case ROTATE -> element.facing == Facing.FIXED ? Operation.ROTATE : Operation.ROTATE_Z;
				case SCALE -> Operation.SCALE_X | Operation.SCALE_Y;
			};
		}

		float[] matrix = model.get(new float[16]);
		float[] before = matrix.clone();
		ImGuizmo.setOrthographic(screen);
		ImGuizmo.setID(element.id.hashCode());
		int mode = localAxes || tool == Tool.SCALE ? Mode.LOCAL : Mode.WORLD;
		float[] snapValues = snap ? snapValues(screen, rectH) : null;
		if (snapValues != null) {
			ImGuizmo.manipulate(view, projection, operation, mode, matrix, null, snapValues);
		} else {
			ImGuizmo.manipulate(view, projection, operation, mode, matrix);
		}

		if (ImGuizmo.isUsing()) {
			ChannelValues updated = start;
			if (!java.util.Arrays.equals(before, matrix)) {
				Matrix4f changed = new Matrix4f().set(matrix);
				updated = screen ? applyScreen(start, changed, rectW, rectH) : applyWorld(element, start, changed, camera);
			}
			drag = new Drag(element.id, updated);
		} else if (drag != null) {
			commit(project, editorState, drag);
			drag = null;
		}
	}

	private static float[] snapValues(boolean screen, float rectH) {
		return switch (tool) {
			case MOVE -> {
				float step = screen ? rectH * SNAP_SCREEN_MOVE_FRACTION : SNAP_WORLD_MOVE;
				yield new float[]{step, step, step};
			}
			case ROTATE -> new float[]{SNAP_ROTATE, SNAP_ROTATE, SNAP_ROTATE};
			case SCALE -> new float[]{SNAP_SCALE, SNAP_SCALE, SNAP_SCALE};
		};
	}

	/** Writes a finished drag: to the base value, or to a keyframe at the cursor if the transform is animated or auto-key is on. */
	private static void commit(BillboardProject project, EditorState editorState, Drag finished) {
		Element element = project.get(finished.elementId());
		if (element == null) {
			return;
		}
		commitValues(project, editorState, element, finished.values());
	}

	/** Applies edited channel values the same way the inspector does. */
	public static void commitValues(BillboardProject project, EditorState editorState, Element element, ChannelValues values) {
		int tick = PlaybackTime.cursorTick();
		TrackEditor.KeyState state = tick < 0 ? TrackEditor.KeyState.NOT_ANIMATED : TrackEditor.keyState(editorState, element.id, values.channel, tick);
		if (state == TrackEditor.KeyState.NOT_ANIMATED && !autoKey) {
			element.setBase(values.channel, values.copy());
			project.markDirty();
		} else {
			TrackEditor.setKeyframe(editorState, element, values, tick);
		}
	}

	private static void cancelDrag() {
		drag = null;
	}

	// ------------------------------------------------------------------------------------------------
	// Matrix <-> transform values
	// ------------------------------------------------------------------------------------------------

	/** A screen element in frame pixels with y up (ImGuizmo's convention): translate, roll, scale. */
	public static Matrix4f screenGizmoModel(ChannelValues transform, float width, float height) {
		return new Matrix4f()
			.translate(transform.getFloat(Channel.X) * width, (1 - transform.getFloat(Channel.Y)) * height, 0)
			.rotateZ((float) Math.toRadians(transform.get(Channel.ROLL)))
			.scale(transform.getFloat(Channel.SCALE_X), transform.getFloat(Channel.SCALE_Y), 1);
	}

	public static ChannelValues applyScreen(ChannelValues start, Matrix4f model, float width, float height) {
		ChannelValues out = start.copy();
		Vector3f translation = model.getTranslation(new Vector3f());
		out.set(Channel.X, translation.x / width);
		out.set(Channel.Y, 1 - translation.y / height);
		double roll = Math.toDegrees(Math.atan2(model.m01(), model.m00()));
		out.set(Channel.ROLL, unwrap(roll, start.get(Channel.ROLL)));
		Vector3f scale = model.getScale(new Vector3f());
		out.set(Channel.SCALE_X, Math.copySign(scale.x, start.get(Channel.SCALE_X)));
		out.set(Channel.SCALE_Y, Math.copySign(scale.y, start.get(Channel.SCALE_Y)));
		return out;
	}

	/** Reads position, rotation and scale back from a camera-relative world model matrix (see {@link ElementGeometry#worldModel}). */
	public static ChannelValues applyWorld(Element element, ChannelValues start, Matrix4f model, OverlayRenderer.SceneCamera camera) {
		ChannelValues out = start.copy();

		Vector3f translation = model.getTranslation(new Vector3f());
		Vec3 world = camera.position().add(translation.x, translation.y, translation.z);
		double[] position = ElementGeometry.transformPositionFor(element, world);
		if (position != null) {
			out.set(Channel.X, position[0]);
			out.set(Channel.Y, position[1]);
			out.set(Channel.Z, position[2]);
		}

		Quaternionf total = model.getNormalizedRotation(new Quaternionf());
		Quaternionf own;
		switch (element.facing) {
			case FIXED -> own = new Quaternionf().rotateY((float) Math.toRadians(ElementGeometry.attachYaw(element))).mul(total);
			case FACE_CAMERA -> own = new Quaternionf(camera.orientation()).conjugate().mul(total);
			case Y_AXIS -> {
				double dx = world.x - camera.position().x;
				double dz = world.z - camera.position().z;
				own = new Quaternionf().rotateY((float) -Math.atan2(-dx, -dz)).mul(total);
			}
			default -> own = total;
		}
		Vector3f euler = own.getEulerAnglesYXZ(new Vector3f());
		out.set(Channel.PITCH, unwrap(Math.toDegrees(euler.x), start.get(Channel.PITCH)));
		out.set(Channel.YAW, unwrap(Math.toDegrees(euler.y), start.get(Channel.YAW)));
		out.set(Channel.ROLL, unwrap(Math.toDegrees(euler.z), start.get(Channel.ROLL)));

		Vector3f scale = model.getScale(new Vector3f());
		out.set(Channel.SCALE_X, Math.copySign(scale.x, start.get(Channel.SCALE_X)));
		out.set(Channel.SCALE_Y, Math.copySign(scale.y, start.get(Channel.SCALE_Y)));

		// Only let the active tool change its own values, so float noise can't creep into the others.
		if (tool != Tool.MOVE) {
			out.set(Channel.X, start.get(Channel.X));
			out.set(Channel.Y, start.get(Channel.Y));
			out.set(Channel.Z, start.get(Channel.Z));
		}
		if (tool != Tool.ROTATE) {
			out.set(Channel.PITCH, start.get(Channel.PITCH));
			out.set(Channel.YAW, start.get(Channel.YAW));
			out.set(Channel.ROLL, start.get(Channel.ROLL));
		}
		if (tool != Tool.SCALE) {
			out.set(Channel.SCALE_X, start.get(Channel.SCALE_X));
			out.set(Channel.SCALE_Y, start.get(Channel.SCALE_Y));
		}
		return out;
	}

	/** The angle equal to {@code angle} (mod 360) closest to {@code previous}, so spins past 360° survive edits. */
	static double unwrap(double angle, double previous) {
		return angle + 360.0 * Math.round((previous - angle) / 360.0);
	}

	/**
	 * Minecraft's projection uses reversed Z and the device's depth range, which ImGuizmo doesn't expect when it
	 * casts mouse rays. Rebuild an equivalent standard OpenGL projection from the field of view and aspect ratio.
	 */
	static Matrix4f gizmoProjection(Matrix4f minecraft) {
		if (minecraft.m23() == 0) {
			return new Matrix4f(minecraft); // orthographic
		}
		float m11 = Math.abs(minecraft.m11());
		float fovY = (float) (2 * Math.atan(1 / m11));
		float aspect = m11 / Math.abs(minecraft.m00());
		return new Matrix4f().setPerspective(fovY, aspect, 0.05f, 1024f);
	}

	/** Where a world point appears in the game frame, as 0-1 fractions (0,0 = top-left), or null if behind the camera. */
	public static float @Nullable [] frameFraction(Vec3 world) {
		OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
		if (camera == null) {
			return null;
		}
		Vec3 relative = world.subtract(camera.position());
		Matrix4f clip = new Matrix4f(gizmoProjection(camera.projection())).mul(camera.viewRotation());
		Vector4f p = clip.transform(new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1));
		if (p.w <= 0.01f) {
			return null;
		}
		return new float[]{p.x / p.w * 0.5f + 0.5f, 0.5f - p.y / p.w * 0.5f};
	}

	// ------------------------------------------------------------------------------------------------
	// Selection outline
	// ------------------------------------------------------------------------------------------------

	private static void drawOutline(Element element, ChannelValues transform, ChannelValues content, OverlayRenderer.SceneCamera camera,
	                                float rectX, float rectY, float rectW, float rectH) {
		ImDrawList drawList = ImGui.getWindowDrawList();
		float[][] points = new float[4][];
		if (element.space == Space.SCREEN) {
			var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			ElementGeometry.Bounds bounds = ElementGeometry.localBounds(element, content, target.height);
			if (bounds == null) {
				return;
			}
			Matrix4f model = ElementGeometry.screenModel(transform, target.width, target.height);
			float[][] corners = corners(bounds);
			for (int i = 0; i < 4; i++) {
				Vector4f p = model.transform(new Vector4f(corners[i][0], corners[i][1], 0, 1));
				points[i] = new float[]{rectX + p.x / target.width * rectW, rectY + p.y / target.height * rectH};
			}
		} else {
			ElementGeometry.Bounds bounds = ElementGeometry.localBounds(element, content, 1);
			Matrix4f model = ElementGeometry.worldModel(element, transform, camera.position(), camera.orientation());
			if (bounds == null || model == null) {
				return;
			}
			Matrix4f clip = new Matrix4f(gizmoProjection(camera.projection())).mul(camera.viewRotation()).mul(model);
			float[][] corners = corners(bounds);
			for (int i = 0; i < 4; i++) {
				Vector4f p = clip.transform(new Vector4f(corners[i][0], corners[i][1], 0, 1));
				if (p.w <= 0.01f) {
					return; // behind the camera
				}
				points[i] = new float[]{rectX + (p.x / p.w * 0.5f + 0.5f) * rectW, rectY + (0.5f - p.y / p.w * 0.5f) * rectH};
			}
		}
		drawList.addQuad(points[0][0], points[0][1], points[1][0], points[1][1], points[2][0], points[2][1], points[3][0], points[3][1],
			OUTLINE_COLOUR, 2f);
	}

	private static float[][] corners(ElementGeometry.Bounds bounds) {
		return new float[][]{
			{bounds.minX(), bounds.minY()}, {bounds.maxX(), bounds.minY()}, {bounds.maxX(), bounds.maxY()}, {bounds.minX(), bounds.maxY()}
		};
	}
}
