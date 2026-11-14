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
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.keyframe.TrackEditor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.project.ProjectManager;
import me.zhdg.billboards.render.ElementGeometry;
import me.zhdg.billboards.render.OverlayRenderer;
import me.zhdg.billboards.render.SceneFrame;
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
	private static final int GROUP_OUTLINE_COLOUR = 0x992BC4FF;
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
		SceneFrame scene = SceneFrame.current();
		if (project == null || editorState == null || camera == null || scene == null) {
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
		SceneFrame.Node node = selected == null ? null : scene.node(selected.id);
		if (node == null || selected.hidden) {
			cancelDrag();
		} else {
			Frame frame = new Frame(scene, camera, rectX, rectY, rectW, rectH);
			if (selected.type == ElementType.GROUP) {
				// Outline everything inside the group.
				for (SceneFrame.Node other : scene.drawables(selected.space)) {
					if (project.isWithin(other.element(), selected.id)) {
						drawOutline(frame, other, GROUP_OUTLINE_COLOUR, 1.5f);
					}
				}
			} else {
				drawOutline(frame, node, OUTLINE_COLOUR, 2f);
			}
			manipulate(project, editorState, frame, node);
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

	/** Everything the per-frame drawing and gizmo code needs about the viewport. */
	private record Frame(SceneFrame scene, OverlayRenderer.SceneCamera camera, float rectX, float rectY, float rectW, float rectH) {
	}

	// ------------------------------------------------------------------------------------------------
	// Gizmo
	// ------------------------------------------------------------------------------------------------

	private static void manipulate(BillboardProject project, EditorState editorState, Frame frame, SceneFrame.Node node) {
		Element element = node.element();
		// The node's transform already shows the drag preview, if a drag is in progress.
		ChannelValues start = node.transform();
		boolean screen = element.space == Space.SCREEN;

		float[] view;
		float[] projection;
		Matrix4f model;
		Matrix4f parent;
		int operation;
		if (screen) {
			view = new Matrix4f().get(new float[16]);
			projection = new Matrix4f().setOrtho(0, frame.rectW(), 0, frame.rectH(), -1, 1).get(new float[16]);
			parent = screenGizmoParent(frame.scene(), node, frame.rectW(), frame.rectH());
			model = new Matrix4f(parent).mul(SceneFrame.screenLocal(start, frame.rectW(), frame.rectH()));
			operation = switch (tool) {
				case MOVE -> Operation.TRANSLATE_X | Operation.TRANSLATE_Y;
				case ROTATE -> Operation.ROTATE_Z;
				case SCALE -> Operation.SCALE_X | Operation.SCALE_Y;
			};
		} else {
			model = frame.scene().worldModel(node, frame.camera().position(), frame.camera().orientation());
			parent = frame.scene().parentWorldModel(node, frame.camera().position(), frame.camera().orientation());
			if (model == null) {
				cancelDrag();
				return; // attached to an entity that isn't loaded
			}
			view = frame.camera().viewRotation().get(new float[16]);
			projection = gizmoProjection(frame.camera().projection()).get(new float[16]);
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
		float[] snapValues = snap ? snapValues(screen, frame.rectH()) : null;
		if (snapValues != null) {
			ImGuizmo.manipulate(view, projection, operation, mode, matrix, null, snapValues);
		} else {
			ImGuizmo.manipulate(view, projection, operation, mode, matrix);
		}

		if (ImGuizmo.isUsing()) {
			ChannelValues updated = start;
			if (!java.util.Arrays.equals(before, matrix)) {
				Matrix4f changed = new Matrix4f().set(matrix);
				updated = screen
					? applyScreenLocal(start, new Matrix4f(parent).invert().mul(changed), frame.rectW(), frame.rectH())
					: applyWorld(element, start, changed, frame.camera(), parent);
			}
			drag = new Drag(element.id, updated);
		} else if (drag != null) {
			commit(project, editorState, drag);
			drag = null;
		}
	}

	/**
	 * Maps a screen element's parent frame into ImGuizmo's frame space (pixels, origin bottom-left, y up):
	 * flip y and move the origin to the bottom, then apply the groups the element is in.
	 */
	private static Matrix4f screenGizmoParent(SceneFrame scene, SceneFrame.Node node, float width, float height) {
		return new Matrix4f().translate(0, height, 0).scale(1, -1, 1).mul(scene.parentScreenModel(node, width, height));
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

	/** A top-level screen element in frame pixels with y up (ImGuizmo's convention): translate, roll, scale. */
	public static Matrix4f screenGizmoModel(ChannelValues transform, float width, float height) {
		return new Matrix4f()
			.translate(transform.getFloat(Channel.X) * width, (1 - transform.getFloat(Channel.Y)) * height, 0)
			.rotateZ((float) Math.toRadians(transform.get(Channel.ROLL)))
			.scale(transform.getFloat(Channel.SCALE_X), transform.getFloat(Channel.SCALE_Y), 1);
	}

	/** Reads a top-level screen element's values back from its {@link #screenGizmoModel}. */
	public static ChannelValues applyScreen(ChannelValues start, Matrix4f model, float width, float height) {
		return applyScreenLocal(start, new Matrix4f().translate(0, -height, 0).mul(model), width, height);
	}

	/** Reads values back from a matrix in the element's parent frame (see {@link SceneFrame#screenLocal}). */
	public static ChannelValues applyScreenLocal(ChannelValues start, Matrix4f local, float width, float height) {
		return keepOtherTools(start, decomposeScreen(start, local, width, height));
	}

	/** All transform values from a matrix in the element's parent frame, regardless of the active tool. */
	public static ChannelValues decomposeScreen(ChannelValues start, Matrix4f local, float width, float height) {
		ChannelValues out = start.copy();
		Vector3f translation = local.getTranslation(new Vector3f());
		out.set(Channel.X, translation.x / width);
		out.set(Channel.Y, -translation.y / height);
		double roll = Math.toDegrees(Math.atan2(local.m01(), local.m00()));
		out.set(Channel.ROLL, unwrap(roll, start.get(Channel.ROLL)));
		Vector3f scale = local.getScale(new Vector3f());
		out.set(Channel.SCALE_X, Math.copySign(scale.x, start.get(Channel.SCALE_X)));
		out.set(Channel.SCALE_Y, Math.copySign(scale.y, start.get(Channel.SCALE_Y)));
		return out;
	}

	/** Reads values back from a top-level world element's camera-relative model matrix. */
	public static ChannelValues applyWorld(Element element, ChannelValues start, Matrix4f model, OverlayRenderer.SceneCamera camera) {
		return applyWorld(element, start, model, camera, null);
	}

	/**
	 * Reads position, rotation and scale back from a camera-relative world model matrix (see
	 * {@link SceneFrame#worldModel}); {@code parent} is the model matrix of the element's group, or null.
	 */
	public static ChannelValues applyWorld(Element element, ChannelValues start, Matrix4f model, OverlayRenderer.SceneCamera camera,
	                                       @Nullable Matrix4f parent) {
		return keepOtherTools(start, decomposeWorld(element, start, model, camera, parent));
	}

	/** All transform values from a camera-relative world model matrix, regardless of the active tool. */
	public static ChannelValues decomposeWorld(Element element, ChannelValues start, Matrix4f model, OverlayRenderer.SceneCamera camera,
	                                           @Nullable Matrix4f parent) {
		ChannelValues out = start.copy();
		Vector3f translation = model.getTranslation(new Vector3f());

		if (parent != null && element.facing == Facing.FIXED) {
			// Inside a group: the element's own transform is the part after the group's.
			Matrix4f local = new Matrix4f(parent).invert().mul(model);
			Vector3f position = local.getTranslation(new Vector3f());
			out.set(Channel.X, position.x);
			out.set(Channel.Y, position.y);
			out.set(Channel.Z, position.z);
			setRotation(out, start, local.getNormalizedRotation(new Quaternionf()));
			Vector3f scale = local.getScale(new Vector3f());
			out.set(Channel.SCALE_X, Math.copySign(scale.x, start.get(Channel.SCALE_X)));
			out.set(Channel.SCALE_Y, Math.copySign(scale.y, start.get(Channel.SCALE_Y)));
			return out;
		}

		if (parent != null) {
			Vector4f position = new Matrix4f(parent).invert().transform(new Vector4f(translation, 1));
			out.set(Channel.X, position.x);
			out.set(Channel.Y, position.y);
			out.set(Channel.Z, position.z);
		} else {
			Vec3 world = camera.position().add(translation.x, translation.y, translation.z);
			double[] position = ElementGeometry.transformPositionFor(element, world);
			if (position != null) {
				out.set(Channel.X, position[0]);
				out.set(Channel.Y, position[1]);
				out.set(Channel.Z, position[2]);
			}
		}

		Quaternionf total = model.getNormalizedRotation(new Quaternionf());
		Quaternionf own = switch (element.facing) {
			case FIXED -> new Quaternionf().rotateY((float) Math.toRadians(ElementGeometry.attachYaw(element))).mul(total);
			case FACE_CAMERA -> new Quaternionf(camera.orientation()).conjugate().mul(total);
			case Y_AXIS -> new Quaternionf().rotateY((float) -Math.atan2(-translation.x, -translation.z)).mul(total);
		};
		setRotation(out, start, own);

		Vector3f scale = model.getScale(new Vector3f());
		Vector3f parentScale = parent == null ? new Vector3f(1, 1, 1) : parent.getScale(new Vector3f());
		out.set(Channel.SCALE_X, Math.copySign(scale.x / Math.max(1e-6f, parentScale.x), start.get(Channel.SCALE_X)));
		out.set(Channel.SCALE_Y, Math.copySign(scale.y / Math.max(1e-6f, parentScale.y), start.get(Channel.SCALE_Y)));
		return out;
	}

	/**
	 * The transform an element needs to stay exactly where it is when it moves into {@code newParent} (null for the top
	 * level). Uses the element's and groups' values at the current frame. Returns null if it can't be worked out (e.g.
	 * a world element whose group is attached to an entity that isn't loaded).
	 */
	public static @Nullable ChannelValues reparentedTransform(SceneFrame scene, SceneFrame.Node node, @Nullable SceneFrame.Node newParent) {
		Element element = node.element();
		if (element.space == Space.SCREEN) {
			var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			float width = target.width;
			float height = target.height;
			Matrix4f model = scene.screenModel(node, width, height);
			Matrix4f parent = newParent == null ? new Matrix4f().scale(1, -1, 1) : scene.screenModel(newParent, width, height);
			return decomposeScreen(node.transform(), parent.invert().mul(model), width, height);
		}
		OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
		if (camera == null) {
			return null;
		}
		Matrix4f model = scene.worldModel(node, camera.position(), camera.orientation());
		Matrix4f parent = newParent == null ? null : scene.worldModel(newParent, camera.position(), camera.orientation());
		if (model == null || (newParent != null && parent == null)) {
			return null;
		}
		return decomposeWorld(element, node.transform(), model, camera, parent);
	}

	private static void setRotation(ChannelValues out, ChannelValues start, Quaternionf rotation) {
		Vector3f euler = rotation.getEulerAnglesYXZ(new Vector3f());
		out.set(Channel.PITCH, unwrap(Math.toDegrees(euler.x), start.get(Channel.PITCH)));
		out.set(Channel.YAW, unwrap(Math.toDegrees(euler.y), start.get(Channel.YAW)));
		out.set(Channel.ROLL, unwrap(Math.toDegrees(euler.z), start.get(Channel.ROLL)));
	}

	/** Only let the active tool change its own values, so float noise can't creep into the others. */
	private static ChannelValues keepOtherTools(ChannelValues start, ChannelValues out) {
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

	private static void drawOutline(Frame frame, SceneFrame.Node node, int colour, float thickness) {
		Element element = node.element();
		ImDrawList drawList = ImGui.getWindowDrawList();
		float[][] points = new float[4][];
		if (element.space == Space.SCREEN) {
			var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			ElementGeometry.Bounds bounds = ElementGeometry.localBounds(element, node.content(), target.height);
			if (bounds == null) {
				return;
			}
			Matrix4f model = frame.scene().screenModel(node, target.width, target.height);
			float[][] corners = corners(bounds);
			for (int i = 0; i < 4; i++) {
				Vector4f p = model.transform(new Vector4f(corners[i][0], corners[i][1], 0, 1));
				points[i] = new float[]{frame.rectX() + p.x / target.width * frame.rectW(), frame.rectY() + p.y / target.height * frame.rectH()};
			}
		} else {
			ElementGeometry.Bounds bounds = ElementGeometry.localBounds(element, node.content(), 1);
			Matrix4f model = frame.scene().worldModel(node, frame.camera().position(), frame.camera().orientation());
			if (bounds == null || model == null) {
				return;
			}
			Matrix4f clip = new Matrix4f(gizmoProjection(frame.camera().projection())).mul(frame.camera().viewRotation()).mul(model);
			float[][] corners = corners(bounds);
			for (int i = 0; i < 4; i++) {
				Vector4f p = clip.transform(new Vector4f(corners[i][0], corners[i][1], 0, 1));
				if (p.w <= 0.01f) {
					return; // behind the camera
				}
				points[i] = new float[]{frame.rectX() + (p.x / p.w * 0.5f + 0.5f) * frame.rectW(), frame.rectY() + (0.5f - p.y / p.w * 0.5f) * frame.rectH()};
			}
		}
		drawList.addQuad(points[0][0], points[0][1], points[1][0], points[1][1], points[2][0], points[2][1], points[3][0], points[3][1],
			colour, thickness);
	}

	private static float[][] corners(ElementGeometry.Bounds bounds) {
		return new float[][]{
			{bounds.minX(), bounds.minY()}, {bounds.maxX(), bounds.minY()}, {bounds.maxX(), bounds.maxY()}, {bounds.minX(), bounds.maxY()}
		};
	}
}
