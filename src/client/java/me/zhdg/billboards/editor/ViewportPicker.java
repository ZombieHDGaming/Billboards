package me.zhdg.billboards.editor;

import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.render.ElementGeometry;
import me.zhdg.billboards.render.OverlayRenderer;
import me.zhdg.billboards.render.SceneFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Finds the overlay under a point of the viewport. Screen overlays are checked first (they are drawn on top),
 * topmost first; then world overlays along the mouse ray, nearest first. Clicking an element inside a group picks
 * that element; groups are selected from the outliner.
 */
public final class ViewportPicker {

	private ViewportPicker() {
	}

	/**
	 * @param frameX    mouse position as a 0-1 fraction of the frame width
	 * @param frameY    mouse position as a 0-1 fraction of the frame height (0 = top)
	 * @param direction world-space direction of the mouse ray from the camera, or null to skip world overlays
	 */
	public static @Nullable UUID pick(float frameX, float frameY, @Nullable Vec3 direction) {
		UUID screen = pickScreen(frameX, frameY);
		if (screen != null) {
			return screen;
		}
		return direction == null ? null : pickWorld(direction);
	}

	public static @Nullable UUID pickScreen(float frameX, float frameY) {
		SceneFrame scene = SceneFrame.current();
		if (scene == null) {
			return null;
		}
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		float width = target.width;
		float height = target.height;

		List<SceneFrame.Node> nodes = scene.drawables(Space.SCREEN);
		// Same order as drawing, so the last one drawn is on top: check in reverse.
		nodes.sort(Comparator.comparingInt(scene::rootLayer));
		for (int i = nodes.size() - 1; i >= 0; i--) {
			SceneFrame.Node node = nodes.get(i);
			ElementGeometry.Bounds bounds = ElementGeometry.localBounds(node.element(), node.content(), height);
			if (bounds == null) {
				continue;
			}
			Matrix4f inverse = scene.screenModel(node, width, height).invert();
			Vector4f local = inverse.transform(new Vector4f(frameX * width, frameY * height, 0, 1));
			if (bounds.contains(local.x, local.y)) {
				return node.element().id;
			}
		}
		return null;
	}

	public static @Nullable UUID pickWorld(Vec3 direction) {
		OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
		SceneFrame scene = SceneFrame.current();
		if (camera == null || scene == null) {
			return null;
		}
		UUID best = null;
		double bestDistance = Double.MAX_VALUE;
		for (SceneFrame.Node node : scene.drawables(Space.WORLD)) {
			Double distance = intersect(scene, node, camera, direction);
			if (distance != null && distance < bestDistance) {
				bestDistance = distance;
				best = node.element().id;
			}
		}
		return best;
	}

	/** Distance along the ray (from the camera) at which it hits the element's quad, or null if it misses. */
	public static @Nullable Double intersect(SceneFrame scene, SceneFrame.Node node, OverlayRenderer.SceneCamera camera, Vec3 direction) {
		ElementGeometry.Bounds bounds = ElementGeometry.localBounds(node.element(), node.content(), 1);
		Matrix4f model = scene.worldModel(node, camera.position(), camera.orientation());
		if (bounds == null || model == null) {
			return null;
		}
		// Camera-relative coordinates: the ray starts at the origin.
		Matrix4f inverse = model.invert();
		Vector4f origin = inverse.transform(new Vector4f(0, 0, 0, 1));
		Vector4f dir = inverse.transform(new Vector4f((float) direction.x, (float) direction.y, (float) direction.z, 0));
		if (Math.abs(dir.z) < 1e-6) {
			return null; // ray parallel to the element
		}
		float t = -origin.z / dir.z;
		if (t <= 0) {
			return null; // behind the camera
		}
		float x = origin.x + dir.x * t;
		float y = origin.y + dir.y * t;
		return bounds.contains(x, y) ? (double) t * direction.length() : null;
	}
}
