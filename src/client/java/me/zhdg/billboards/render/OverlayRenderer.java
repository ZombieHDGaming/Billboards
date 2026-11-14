package me.zhdg.billboards.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.moulberry.flashback.Flashback;
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
 * Draws overlays. Placement comes from {@link ElementGeometry}; this class turns it into quads.
 */
public final class OverlayRenderer {

	/** Below this opacity (as a byte) vanilla text rendering treats the colour as opaque, so skip the element. */
	private static final float MIN_TEXT_ALPHA = 4f / 255f;

	private OverlayRenderer() {
	}

	/** A resolved element: static settings plus the values for this frame. */
	public record Resolved(Element element, ChannelValues transform, ChannelValues appearance, ChannelValues content) {
	}

	/** The camera of the last rendered level frame, for picking and gizmos in the editor. */
	public record SceneCamera(Vec3 position, Quaternionf orientation, Matrix4f viewRotation, Matrix4f projection) {
	}

	/**
	 * Values shown while the user drags a gizmo, so the drag previews live without writing a keyframe every frame.
	 * Never used while exporting.
	 */
	public interface PreviewSource {
		@Nullable ChannelValues preview(UUID elementId, Channel channel);
	}

	private static @Nullable SceneCamera lastCamera = null;
	private static @Nullable PreviewSource previewSource = null;

	public static @Nullable SceneCamera lastCamera() {
		return lastCamera;
	}

	public static void setPreviewSource(@Nullable PreviewSource source) {
		previewSource = source;
	}

	// ------------------------------------------------------------------------------------------------
	// Passes
	// ------------------------------------------------------------------------------------------------

	/** World-space pass, called at the end of level rendering with the level projection still bound. */
	public static void renderWorld(CameraRenderState camera) {
		lastCamera = new SceneCamera(camera.pos, new Quaternionf(camera.orientation),
			new Matrix4f(camera.viewRotationMatrix), new Matrix4f(camera.projectionMatrix));

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
				Matrix4f model = ElementGeometry.worldModel(resolved.element(), resolved.transform(), cameraPos, camera.orientation);
				if (model == null) {
					continue;
				}
				Matrix4f root = new Matrix4f(camera.viewRotationMatrix).mul(model);
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
				Matrix4f root = ElementGeometry.screenModel(resolved.transform(), width, height);
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

	/** Every visible element of a space with its values for the current frame. */
	public static List<Resolved> resolve(Space space) {
		List<Resolved> result = new ArrayList<>();
		BillboardProject project = ProjectManager.getCurrent();
		if (project == null || project.elements.isEmpty()) {
			return result;
		}
		KeyframeEvaluator.Frame frame = KeyframeEvaluator.evaluate(PlaybackTime.current());

		for (Element element : project.elements) {
			if (element.space != space || element.hidden || (space == Space.WORLD && !ElementGeometry.isPlaced(element))) {
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

	/** A gizmo drag preview if there is one, then the animated value if the channel has a track, then the base value. */
	public static ChannelValues valuesOf(KeyframeEvaluator.Frame frame, Element element, Channel channel) {
		if (previewSource != null && !Flashback.isExporting()) {
			ChannelValues preview = previewSource.preview(element.id, channel);
			if (preview != null) {
				return preview;
			}
		}
		ChannelValues animated = frame.get(element.id, channel);
		return animated != null ? animated : element.base(channel);
	}

	private static double distanceSq(Resolved resolved, Vec3 cameraPos) {
		Vec3 position = ElementGeometry.worldPosition(resolved.element(), resolved.transform());
		return position == null ? 0 : position.distanceToSqr(cameraPos);
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
		ElementGeometry.Bounds bounds = ElementGeometry.localBounds(element, resolved.content(), unit);
		if (bounds == null) {
			return;
		}

		ImageTextures.Entry texture = replayId == null ? null : ImageTextures.get(replayId, element.assetId);
		boolean missing = texture == null;
		if (missing) {
			texture = ImageTextures.placeholder();
		}

		ChannelValues content = resolved.content();
		float u0 = content.getFloat(Channel.CROP_LEFT);
		float v0 = content.getFloat(Channel.CROP_TOP);
		float u1 = content.getFloat(Channel.CROP_RIGHT);
		float v1 = content.getFloat(Channel.CROP_BOTTOM);
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
		buffer.addVertex(root, bounds.minX(), bounds.maxY(), 0).setUv(u0, v0).setColor(color);
		buffer.addVertex(root, bounds.minX(), bounds.minY(), 0).setUv(u0, v1).setColor(color);
		buffer.addVertex(root, bounds.maxX(), bounds.minY(), 0).setUv(u1, v1).setColor(color);
		buffer.addVertex(root, bounds.maxX(), bounds.maxY(), 0).setUv(u1, v0).setColor(color);
	}

	private static void drawText(OverlayBatch batch, Resolved resolved, Matrix4f root, float unit, boolean depthTest) {
		Element element = resolved.element();
		ChannelValues appearance = resolved.appearance();
		if (appearance.get(Channel.OPACITY) < MIN_TEXT_ALPHA) {
			return;
		}
		ElementGeometry.TextLayout layout = ElementGeometry.textLayout(element, resolved.content(), unit);
		if (layout == null) {
			return;
		}

		Font font = Minecraft.getInstance().font;
		float pixel = layout.pixel();
		Matrix4f pose = new Matrix4f(root)
			.translate(-element.pivotX * layout.blockWidth() * pixel, element.pivotY * layout.blockHeight() * pixel, 0)
			.scale(pixel, -pixel, pixel); // glyph y grows downwards

		int color = color(appearance);
		int background = scaleAlpha(element.backgroundColor, appearance.get(Channel.OPACITY));
		GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

		String[] lines = layout.lines();
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int lineWidth = font.width(line);
			float x = switch (element.align) {
				case LEFT -> 0;
				case CENTER -> (layout.blockWidth() - lineWidth) / 2f;
				case RIGHT -> layout.blockWidth() - lineWidth;
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

	private static int scaleAlpha(int argb, double opacity) {
		int alpha = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, opacity)));
		return alpha << 24 | (argb & 0xFFFFFF);
	}
}
