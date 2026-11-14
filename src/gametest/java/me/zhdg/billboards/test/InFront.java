package me.zhdg.billboards.test;

import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.render.ElementGeometry;
import me.zhdg.billboards.render.OverlayRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Places world overlays at fixed spots of the frame: {@link #DISTANCE} blocks in front of the camera (as it was last
 * rendered), facing it and drawn on top of the world. At that distance everything is the same depth from the camera,
 * so positions and sizes in fractions of the frame map straight to blocks.
 */
final class InFront {

	/** Blocks from the camera, along its view direction. */
	static final double DISTANCE = 4;

	private InFront() {
	}

	/** Puts a top-level element's pivot at the frame fraction ({@code fx}, {@code fy}), (0, 0) being top-left. */
	static void place(Element element, float fx, float fy) {
		Vec3 position = at(fx, fy);
		element.facing = Facing.CENTER;
		element.depthTest = false;
		element.transform.set(Channel.X, position.x);
		element.transform.set(Channel.Y, position.y);
		element.transform.set(Channel.Z, position.z);
	}

	/** The world point shown at a frame fraction, {@link #DISTANCE} blocks in front of the camera. */
	static Vec3 at(float fx, float fy) {
		OverlayRenderer.SceneCamera camera = camera();
		Vector3f forward = camera.orientation().transform(new Vector3f(0, 0, -1));
		Vector3f right = camera.orientation().transform(new Vector3f(1, 0, 0));
		Vector3f up = camera.orientation().transform(new Vector3f(0, 1, 0));
		double x = (2 * fx - 1) * DISTANCE / Math.abs(camera.projection().m00());
		double y = (1 - 2 * fy) * DISTANCE / Math.abs(camera.projection().m11());
		return camera.position().add(
			forward.x * DISTANCE + right.x * x + up.x * y,
			forward.y * DISTANCE + right.y * x + up.y * y,
			forward.z * DISTANCE + right.z * x + up.z * y);
	}

	/** Blocks covered by the full frame height at {@link #DISTANCE}: a size of {@code f * frameHeight()} is {@code f} of the frame. */
	static float frameHeight() {
		return (float) (2 * DISTANCE / Math.abs(camera().projection().m11()));
	}

	/** Blocks covered by the full frame width at {@link #DISTANCE}. */
	static float frameWidth() {
		return (float) (2 * DISTANCE / Math.abs(camera().projection().m00()));
	}

	/** The Transform scale that makes one line of text (9 font pixels) {@code fraction} of the frame height. */
	static float textScale(float fraction) {
		return fraction * frameHeight() / (9 * ElementGeometry.TEXT_PIXEL);
	}

	static void scale(Element element, float scale) {
		element.transform.set(Channel.SCALE_X, scale);
		element.transform.set(Channel.SCALE_Y, scale);
	}

	private static OverlayRenderer.SceneCamera camera() {
		OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
		if (camera == null) {
			throw new AssertionError("no level frame has been rendered yet");
		}
		return camera;
	}
}
