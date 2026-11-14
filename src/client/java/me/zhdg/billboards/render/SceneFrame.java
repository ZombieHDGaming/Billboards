package me.zhdg.billboards.render;

import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.project.ProjectManager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every element of the project with its values for one frame, and the group hierarchy resolved: a child's transform
 * is relative to its group, and a group's opacity, tint and visibility apply to everything inside it.
 */
public final class SceneFrame {

	private static final int MAX_DEPTH = 32;

	/**
	 * An element with its values for this frame. {@code appearance} already includes the groups it is in (opacity
	 * and tint multiplied, visibility combined).
	 */
	public record Node(Element element, ChannelValues transform, ChannelValues appearance, ChannelValues content, int depth) {
	}

	private final BillboardProject project;
	private final Map<UUID, Node> nodes = new HashMap<>();
	/** Elements in outliner order: each group followed by its contents. */
	private final List<Node> order = new ArrayList<>();

	private SceneFrame(BillboardProject project) {
		this.project = project;
	}

	/** The scene as it should look right now (in the editor, or at the export's current tick). */
	public static @Nullable SceneFrame current() {
		BillboardProject project = ProjectManager.getCurrent();
		if (project == null) {
			return null;
		}
		return build(project, KeyframeEvaluator.evaluate(PlaybackTime.current()));
	}

	public static SceneFrame build(BillboardProject project, KeyframeEvaluator.Frame frame) {
		SceneFrame scene = new SceneFrame(project);
		scene.addChildren(null, frame, 0, null);
		return scene;
	}

	private void addChildren(@Nullable UUID parentId, KeyframeEvaluator.Frame frame, int depth, @Nullable ChannelValues parentAppearance) {
		if (depth > MAX_DEPTH) {
			return;
		}
		for (Element element : this.project.children(parentId)) {
			if (this.nodes.containsKey(element.id)) {
				continue; // a loop in hand-edited data
			}
			ChannelValues appearance = OverlayRenderer.valuesOf(frame, element, Channel.APPEARANCE);
			if (parentAppearance != null) {
				appearance = combine(parentAppearance, appearance);
			}
			Node node = new Node(element, OverlayRenderer.valuesOf(frame, element, Channel.TRANSFORM), appearance,
				OverlayRenderer.valuesOf(frame, element, Channel.CONTENT), depth);
			this.nodes.put(element.id, node);
			this.order.add(node);
			if (element.type == ElementType.GROUP) {
				this.addChildren(element.id, frame, depth + 1, appearance);
			}
		}
	}

	/** A group's appearance applied to a child: opacity and tint multiply, both must be visible. */
	private static ChannelValues combine(ChannelValues parent, ChannelValues child) {
		ChannelValues out = child.copy();
		out.set(Channel.OPACITY, parent.get(Channel.OPACITY) * child.get(Channel.OPACITY));
		out.set(Channel.RED, parent.get(Channel.RED) * child.get(Channel.RED));
		out.set(Channel.GREEN, parent.get(Channel.GREEN) * child.get(Channel.GREEN));
		out.set(Channel.BLUE, parent.get(Channel.BLUE) * child.get(Channel.BLUE));
		out.set(Channel.VISIBLE, parent.get(Channel.VISIBLE) >= 0.5 && child.get(Channel.VISIBLE) >= 0.5 ? 1 : 0);
		return out;
	}

	public @Nullable Node node(UUID id) {
		return this.nodes.get(id);
	}

	public @Nullable Node parent(Node node) {
		return node.element().parentId == null ? null : this.nodes.get(node.element().parentId);
	}

	public List<Node> nodes() {
		return this.order;
	}

	/** Whether the node and all the groups it is in are shown (not hidden, visible, opacity above zero, placeable). */
	public boolean isShown(Node node) {
		Node current = node;
		for (int i = 0; current != null && i <= MAX_DEPTH; i++) {
			if (current.element().hidden) {
				return false;
			}
			Node parent = this.parent(current);
			if (parent == null && current.element().space == Space.WORLD && !ElementGeometry.isPlaced(current.element())) {
				return false;
			}
			current = parent;
		}
		return node.appearance().get(Channel.VISIBLE) >= 0.5 && node.appearance().get(Channel.OPACITY) > 0;
	}

	/** Everything of a space that draws something this frame, in outliner order (groups themselves draw nothing). */
	public List<Node> drawables(Space space) {
		List<Node> result = new ArrayList<>();
		for (Node node : this.order) {
			if (node.element().space == space && node.element().type != ElementType.GROUP && this.isShown(node)) {
				result.add(node);
			}
		}
		return result;
	}

	/** The layer of the top-level element a node is in, which decides screen draw order. */
	public int rootLayer(Node node) {
		Node current = node;
		for (int i = 0; i <= MAX_DEPTH; i++) {
			Node parent = this.parent(current);
			if (parent == null) {
				return current.element().layer;
			}
			current = parent;
		}
		return current.element().layer;
	}

	// ------------------------------------------------------------------------------------------------
	// World
	// ------------------------------------------------------------------------------------------------

	/** Camera-relative model matrix (see {@link ElementGeometry#worldModel}), including the groups the node is in. */
	public @Nullable Matrix4f worldModel(Node node, Vec3 cameraPos, Quaternionfc cameraOrientation) {
		return this.worldModel(node, cameraPos, cameraOrientation, 0);
	}

	/** The model matrix of the group a node is in, or null at the top level. */
	public @Nullable Matrix4f parentWorldModel(Node node, Vec3 cameraPos, Quaternionfc cameraOrientation) {
		Node parent = this.parent(node);
		return parent == null ? null : this.worldModel(parent, cameraPos, cameraOrientation, 0);
	}

	private @Nullable Matrix4f worldModel(Node node, Vec3 cameraPos, Quaternionfc cameraOrientation, int depth) {
		Node parent = this.parent(node);
		if (parent == null || depth > MAX_DEPTH) {
			return ElementGeometry.worldModel(node.element(), node.transform(), cameraPos, cameraOrientation);
		}
		Matrix4f parentModel = this.worldModel(parent, cameraPos, cameraOrientation, depth + 1);
		if (parentModel == null) {
			return null;
		}
		ChannelValues t = node.transform();
		Vector3f localPosition = new Vector3f(t.getFloat(Channel.X), t.getFloat(Channel.Y), t.getFloat(Channel.Z));
		if (node.element().facing == Facing.FIXED) {
			return new Matrix4f(parentModel)
				.translate(localPosition)
				.rotate(ElementGeometry.rotation(t))
				.scale(t.getFloat(Channel.SCALE_X), t.getFloat(Channel.SCALE_Y), 1);
		}
		// Camera-facing children: placed by the group, but oriented towards the camera like top-level elements.
		Vector4f position = parentModel.transform(new Vector4f(localPosition, 1));
		Vector3f parentScale = parentModel.getScale(new Vector3f());
		Matrix4f matrix = new Matrix4f().translate(position.x, position.y, position.z);
		if (node.element().facing == Facing.FACE_CAMERA) {
			matrix.rotate(cameraOrientation);
		} else {
			matrix.rotateY((float) Math.atan2(-position.x, -position.z));
		}
		return matrix.rotate(ElementGeometry.rotation(t))
			.scale(t.getFloat(Channel.SCALE_X) * parentScale.x, t.getFloat(Channel.SCALE_Y) * parentScale.y, 1);
	}

	/** Where a node's pivot is in the world, or null if it can't be placed. */
	public @Nullable Vec3 worldPosition(Node node, Vec3 cameraPos, Quaternionfc cameraOrientation) {
		Matrix4f model = this.worldModel(node, cameraPos, cameraOrientation);
		if (model == null) {
			return null;
		}
		Vector3f translation = model.getTranslation(new Vector3f());
		return cameraPos.add(translation.x, translation.y, translation.z);
	}

	// ------------------------------------------------------------------------------------------------
	// Screen
	// ------------------------------------------------------------------------------------------------

	/**
	 * Model matrix in frame pixels (origin top-left, y down) for a frame of the given size, including groups.
	 * Each element is {@code translate(x * width, -y * height) · roll · scale} in its parent's y-up frame; the top
	 * level's parent just flips y.
	 */
	public Matrix4f screenModel(Node node, float width, float height) {
		return this.parentScreenModel(node, width, height).mul(screenLocal(node.transform(), width, height));
	}

	public Matrix4f parentScreenModel(Node node, float width, float height) {
		Matrix4f matrix = new Matrix4f().scale(1, -1, 1);
		List<Node> chain = new ArrayList<>();
		for (Node parent = this.parent(node); parent != null && chain.size() <= MAX_DEPTH; parent = this.parent(parent)) {
			chain.addFirst(parent);
		}
		for (Node parent : chain) {
			matrix.mul(screenLocal(parent.transform(), width, height));
		}
		return matrix;
	}

	public static Matrix4f screenLocal(ChannelValues transform, float width, float height) {
		return new Matrix4f()
			.translate(transform.getFloat(Channel.X) * width, -transform.getFloat(Channel.Y) * height, 0)
			.rotateZ((float) Math.toRadians(transform.get(Channel.ROLL)))
			.scale(transform.getFloat(Channel.SCALE_X), transform.getFloat(Channel.SCALE_Y), 1);
	}
}
