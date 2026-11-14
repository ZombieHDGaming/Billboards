package me.zhdg.billboards.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.blaze3d.systems.RenderSystem;
import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.project.ProjectManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Draws overlays. Both passes lay elements out in a local frame where +X is right and +Y is up as seen from the
 * front, with the element's pivot at the origin; only the root matrix differs between world and screen space.
 */
public final class OverlayRenderer {

	/** Below this opacity (as a byte) vanilla text rendering treats the colour as opaque, so skip the element. */
	private static final float MIN_TEXT_ALPHA = 4f / 255f;

	private OverlayRenderer() {
	}

	/** A resolved element: static settings plus the animated values for this frame. */
	private record Resolved(Element element, ChannelValues transform, ChannelValues appearance, ChannelValues content) {
	}

	// ------------------------------------------------------------------------------------------------
	// Passes
	// ------------------------------------------------------------------------------------------------

	/** World-space pass, called at the end of level rendering with the level projection still bound. */
	public static void renderWorld(CameraRenderState camera) {
		List<Resolved> elements = resolve(Space.WORLD);
		if (elements.isEmpty()) {
			return;
		}

		Vec3 cameraPos = camera.pos;
		// Depth-tested elements first, far to near, then always-on-top elements by layer.
		elements.sort(Comparator
			.comparingInt((Resolved r) -> r.element().depthTest ? 0 : 1)
			.thenComparingInt(r -> r.element().depthTest ? 0 : r.element().layer)
			.thenComparingDouble(r -> r.element().depthTest ? -distanceSq(r, cameraPos) : 0));

		UUID replayId = ProjectManager.currentReplayId();
		try (OverlayBatch batch = new OverlayBatch()) {
			for (Resolved resolved : elements) {
				Matrix4f root = worldRoot(resolved, camera);
				drawElement(batch, resolved, root, 1.0f, resolved.element().depthTest, replayId);
			}
			batch.draw("billboards world overlays", true);
		}
	}

	/** Screen-space pass, called after the level (and hand) are drawn, before the HUD. Uses its own orthographic projection. */
	public static void renderScreen() {
		List<Resolved> elements = resolve(Space.SCREEN);
		if (elements.isEmpty()) {
			return;
		}
		elements.sort(Comparator.comparingInt(r -> r.element().layer));

		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		int width = target.width;
		int height = target.height;

		UUID replayId = ProjectManager.currentReplayId();
		ScreenProjection.begin(width, height);
		try (OverlayBatch batch = new OverlayBatch()) {
			for (Resolved resolved : elements) {
				ChannelValues t = resolved.transform();
				Matrix4f root = new Matrix4f()
					.translate(t.getFloat(Channel.X) * width, t.getFloat(Channel.Y) * height, 0)
					.scale(1, -1, 1) // screen pixels grow downwards; the local frame is y-up
					.rotateZ((float) Math.toRadians(t.get(Channel.ROLL)))
					.scale(t.getFloat(Channel.SCALE_X), t.getFloat(Channel.SCALE_Y), 1);
				// Sizes are fractions of the frame height.
				drawElement(batch, resolved, root, height, false, replayId);
			}
			batch.draw("billboards screen overlays", false);
		} finally {
			ScreenProjection.end();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Resolution
	// ------------------------------------------------------------------------------------------------

	private static List<Resolved> resolve(Space space) {
		List<Resolved> result = new ArrayList<>();
		BillboardProject project = ProjectManager.getCurrent();
		if (project == null || project.elements.isEmpty()) {
			return result;
		}
		KeyframeEvaluator.Frame frame = KeyframeEvaluator.evaluate(PlaybackTime.current());

		for (Element element : project.elements) {
			if (element.space != space || element.hidden) {
				continue;
			}
			ChannelValues appearance = valuesOf(frame, element, Channel.APPEARANCE);
			if (appearance.get(Channel.VISIBLE) < 0.5 || appearance.get(Channel.OPACITY) <= 0) {
				continue;
			}
			result.add(new Resolved(element, valuesOf(frame, element, Channel.TRANSFORM), appearance, valuesOf(frame, element, Channel.CONTENT)));
		}
		return result;
	}

	/** The animated value if the channel has a track, otherwise the element's base value. */
	public static ChannelValues valuesOf(KeyframeEvaluator.Frame frame, Element element, Channel channel) {
		ChannelValues animated = frame.get(element.id, channel);
		return animated != null ? animated : element.base(channel);
	}

	private static double distanceSq(Resolved resolved, Vec3 cameraPos) {
		ChannelValues t = resolved.transform();
		double dx = t.get(Channel.X) - cameraPos.x;
		double dy = t.get(Channel.Y) - cameraPos.y;
		double dz = t.get(Channel.Z) - cameraPos.z;
		return dx * dx + dy * dy + dz * dz;
	}

	/** View-space matrix for a world element: camera-relative position, then facing, then the element's rotation and scale. */
	private static Matrix4f worldRoot(Resolved resolved, CameraRenderState camera) {
		Element element = resolved.element();
		ChannelValues t = resolved.transform();
		double dx = t.get(Channel.X) - camera.pos.x;
		double dy = t.get(Channel.Y) - camera.pos.y;
		double dz = t.get(Channel.Z) - camera.pos.z;

		Matrix4f matrix = new Matrix4f(camera.viewRotationMatrix).translate((float) dx, (float) dy, (float) dz);
		switch (element.facing) {
			case FACE_CAMERA -> matrix.rotate(camera.orientation);
			// The element's front (+Z) points at the camera, rotating around Y only.
			case Y_AXIS -> matrix.rotateY((float) Math.atan2(-dx, -dz));
			case FIXED -> {
			}
		}
		matrix.rotate(rotation(t));
		matrix.scale(t.getFloat(Channel.SCALE_X), t.getFloat(Channel.SCALE_Y), 1);
		return matrix;
	}

	/** Element rotation: yaw around Y, then pitch around X, then roll around Z (degrees). */
	public static Quaternionf rotation(ChannelValues transform) {
		return new Quaternionf()
			.rotateY((float) Math.toRadians(transform.get(Channel.YAW)))
			.rotateX((float) Math.toRadians(transform.get(Channel.PITCH)))
			.rotateZ((float) Math.toRadians(transform.get(Channel.ROLL)));
	}

	// ------------------------------------------------------------------------------------------------
	// Element drawing
	// ------------------------------------------------------------------------------------------------

	/**
	 * @param unit size multiplier: 1 in the world (sizes are in blocks), the frame height on screen
	 */
	private static void drawElement(OverlayBatch batch, Resolved resolved, Matrix4f root, float unit, boolean depthTest, @Nullable UUID replayId) {
		if (resolved.element().type == ElementType.IMAGE) {
			drawImage(batch, resolved, root, unit, depthTest, replayId);
		} else {
			drawText(batch, resolved, root, unit, depthTest);
		}
	}

	private static int color(ChannelValues appearance) {
		int a = channelByte(appearance.get(Channel.OPACITY));
		int r = channelByte(appearance.get(Channel.RED));
		int g = channelByte(appearance.get(Channel.GREEN));
		int b = channelByte(appearance.get(Channel.BLUE));
		return a << 24 | r << 16 | g << 8 | b;
	}

	private static int channelByte(double value) {
		return (int) Math.round(Math.max(0, Math.min(1, value)) * 255);
	}

	private static void drawImage(OverlayBatch batch, Resolved resolved, Matrix4f root, float unit, boolean depthTest, @Nullable UUID replayId) {
		Element element = resolved.element();
		ImageTextures.Entry texture = replayId == null ? null : ImageTextures.get(replayId, element.assetId);
		boolean missing = texture == null;
		if (missing) {
			texture = ImageTextures.placeholder();
		}

		ChannelValues content = resolved.content();
		float u0 = clamp01(content.getFloat(Channel.CROP_LEFT));
		float v0 = clamp01(content.getFloat(Channel.CROP_TOP));
		float u1 = clamp01(content.getFloat(Channel.CROP_RIGHT));
		float v1 = clamp01(content.getFloat(Channel.CROP_BOTTOM));
		if (u1 <= u0 || v1 <= v0) {
			return;
		}

		float width = element.width * unit;
		float height = element.height * unit;
		// Cropping trims the quad but keeps the remaining pixels where they were.
		float left = (u0 - element.pivotX) * width;
		float right = (u1 - element.pivotX) * width;
		float top = (element.pivotY - v0) * height;
		float bottom = (element.pivotY - v1) * height;
		if (missing) {
			// Tile the checkerboard rather than stretching two pixels across the whole quad.
			u0 = 0;
			v0 = 0;
			u1 = Math.max(1, element.width * 4);
			v1 = Math.max(1, element.height * 4);
		}

		int color = color(resolved.appearance());
		GpuSampler sampler = missing
			? RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)
			: RenderSystem.getSamplerCache().getClampToEdge(element.pixelated ? FilterMode.NEAREST : FilterMode.LINEAR);
		VertexConsumer buffer = batch.begin(OverlayPipelines.get(depthTest, false), texture.view(), sampler);
		buffer.addVertex(root, left, top, 0).setUv(u0, v0).setColor(color);
		buffer.addVertex(root, left, bottom, 0).setUv(u0, v1).setColor(color);
		buffer.addVertex(root, right, bottom, 0).setUv(u1, v1).setColor(color);
		buffer.addVertex(root, right, top, 0).setUv(u1, v0).setColor(color);
	}

	private static void drawText(OverlayBatch batch, Resolved resolved, Matrix4f root, float unit, boolean depthTest) {
		Element element = resolved.element();
		ChannelValues appearance = resolved.appearance();
		if (appearance.get(Channel.OPACITY) < MIN_TEXT_ALPHA) {
			return;
		}

		String text = revealed(resolved.content());
		if (text.isEmpty()) {
			return;
		}

		Font font = Minecraft.getInstance().font;
		String[] lines = text.split("\n", -1);
		int blockWidth = 0;
		for (String line : lines) {
			blockWidth = Math.max(blockWidth, font.width(line));
		}
		int blockHeight = lines.length * font.lineHeight;
		if (blockWidth == 0) {
			return;
		}

		// One font pixel in local units; fontSize is the height of one line.
		float pixel = element.fontSize * unit / font.lineHeight;
		Matrix4f pose = new Matrix4f(root)
			.translate(-element.pivotX * blockWidth * pixel, element.pivotY * blockHeight * pixel, 0)
			.scale(pixel, -pixel, pixel); // glyph y grows downwards

		int color = color(appearance);
		int background = scaleAlpha(element.backgroundColor, appearance.get(Channel.OPACITY));
		GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int lineWidth = font.width(line);
			float x = switch (element.align) {
				case LEFT -> 0;
				case CENTER -> (blockWidth - lineWidth) / 2f;
				case RIGHT -> blockWidth - lineWidth;
			};
			Font.PreparedText prepared = font.prepareText(line, x, i * font.lineHeight, color, element.textShadow, background);
			prepared.visit(new Font.GlyphVisitor() {
				@Override
				public void acceptRenderable(TextRenderable renderable) {
					boolean grayscale = renderable.guiPipeline() == RenderPipelines.GUI_TEXT_GRAYSCALE;
					VertexConsumer buffer = batch.begin(OverlayPipelines.get(depthTest, grayscale), renderable.textureView(), sampler);
					// "Flat": no z offsets between shadow, glyph and background. Vanilla relies on depth testing to
					// keep the glyph above its shadow; overlays are drawn strictly in order instead, and the offsets
					// would push glyphs out of the screen pass's depth range.
					renderable.render(pose, buffer, LightCoordsUtil.FULL_BRIGHT, true);
				}
			});
		}
	}

	/** The part of the text shown by the reveal fraction, counting code points (newlines don't count). */
	static String revealed(ChannelValues content) {
		String text = content.text == null ? "" : content.text;
		double reveal = content.get(Channel.REVEAL);
		if (reveal >= 1) {
			return text;
		}
		if (reveal <= 0) {
			return "";
		}
		int visible = (int) text.codePoints().filter(c -> c != '\n').count();
		int shown = (int) Math.floor(visible * reveal);
		StringBuilder builder = new StringBuilder();
		int count = 0;
		for (int i = 0; i < text.length() && count < shown; ) {
			int codePoint = text.codePointAt(i);
			builder.appendCodePoint(codePoint);
			if (codePoint != '\n') {
				count++;
			}
			i += Character.charCount(codePoint);
		}
		return builder.toString();
	}

	private static int scaleAlpha(int argb, double opacity) {
		int alpha = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, opacity)));
		return alpha << 24 | (argb & 0xFFFFFF);
	}

	private static float clamp01(float value) {
		return Math.max(0, Math.min(1, value));
	}
}
