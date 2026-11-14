package me.zhdg.billboards.editor;

import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.render.ElementGeometry;
import me.zhdg.billboards.render.OverlayRenderer;
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
 * topmost layer first; then world overlays along the mouse ray, nearest first.
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
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		float width = target.width;
		float height = target.height;

		List<OverlayRenderer.Resolved> elements = OverlayRenderer.resolve(Space.SCREEN);
		// Drawn in layer order (stable), so the last one drawn is on top: check in reverse.
		elements.sort(Comparator.comparingInt(r -> r.element().layer));
		for (int i = elements.size() - 1; i >= 0; i--) {
			OverlayRenderer.Resolved resolved = elements.get(i);
			ElementGeometry.Bounds bounds = ElementGeometry.localBounds(resolved.element(), resolved.content(), height);
			if (bounds == null) {
				continue;
			}
			Matrix4f inverse = ElementGeometry.screenModel(resolved.transform(), width, height).invert();
			Vector4f local = inverse.transform(new Vector4f(frameX * width, frameY * height, 0, 1));
			if (bounds.contains(local.x, local.y)) {
				return resolved.element().id;
			}
		}
		return null;
	}

	public static @Nullable UUID pickWorld(Vec3 direction) {
		OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
		if (camera == null) {
			return null;
		}
		UUID best = null;
		double bestDistance = Double.MAX_VALUE;
		for (OverlayRenderer.Resolved resolved : OverlayRenderer.resolve(Space.WORLD)) {
			Double distance = intersect(resolved, camera, direction);
			if (distance != null && distance < bestDistance) {
				bestDistance = distance;
				best = resolved.element().id;
			}
		}
		return best;
	}

	/** Distance along the ray (from the camera) at which it hits the element's quad, or null if it misses. */
	public static @Nullable Double intersect(OverlayRenderer.Resolved resolved, OverlayRenderer.SceneCamera camera, Vec3 direction) {
		ElementGeometry.Bounds bounds = ElementGeometry.localBounds(resolved.element(), resolved.content(), 1);
		Matrix4f model = ElementGeometry.worldModel(resolved.element(), resolved.transform(), camera.position(), camera.orientation());
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
