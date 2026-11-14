package me.zhdg.billboards.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.moulberry.flashback.Flashback;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.BlendMode;
import me.zhdg.billboards.model.Effects;
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

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Draws overlays. Placement comes from {@link SceneFrame} / {@link ElementGeometry}; this class turns it into quads.
 */
public final class OverlayRenderer {

	/** Below this opacity (as a byte) vanilla text rendering treats the colour as opaque, so skip the element. */
	private static final float MIN_TEXT_ALPHA = 4f / 255f;

	private OverlayRenderer() {
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

		SceneFrame scene = SceneFrame.current();
		if (scene == null) {
			return;
		}
		List<SceneFrame.Node> nodes = scene.drawables(Space.WORLD);
		if (nodes.isEmpty()) {
			return;
		}

		Vec3 cameraPos = camera.pos;
		// Depth-tested elements first, far to near, then always-on-top elements by layer.
		nodes.sort(Comparator
			.comparingInt((SceneFrame.Node n) -> n.element().depthTest ? 0 : 1)
			.thenComparingInt(n -> n.element().depthTest ? 0 : scene.rootLayer(n))
			.thenComparingDouble(n -> {
				if (!n.element().depthTest) {
					return 0;
				}
				Vec3 position = scene.worldPosition(n, cameraPos, camera.orientation);
				return position == null ? 0 : -position.distanceToSqr(cameraPos);
			}));

		UUID replayId = ProjectManager.currentReplayId();
		try (OverlayBatch batch = new OverlayBatch()) {
			for (SceneFrame.Node node : nodes) {
				Matrix4f model = scene.worldModel(node, cameraPos, camera.orientation);
				if (model == null) {
					continue;
				}
				Matrix4f root = new Matrix4f(camera.viewRotationMatrix).mul(model);
				drawElement(batch, node, root, 1.0f, node.element().depthTest, replayId);
			}
			batch.draw("billboards world overlays", true);
		}
	}

	/** Screen-space pass, called after the level (and hand) are drawn, before the HUD. Uses its own orthographic projection. */
	public static void renderScreen() {
		SceneFrame scene = SceneFrame.current();
		if (scene == null) {
			return;
		}
		List<SceneFrame.Node> nodes = scene.drawables(Space.SCREEN);
		if (nodes.isEmpty()) {
			return;
		}
		// Stable sort: within a layer, outliner order (a group's contents in their own order).
		nodes.sort(Comparator.comparingInt(scene::rootLayer));

		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		int width = target.width;
		int height = target.height;

		UUID replayId = ProjectManager.currentReplayId();
		ScreenProjection.begin(width, height);
		try (OverlayBatch batch = new OverlayBatch()) {
			for (SceneFrame.Node node : nodes) {
				Matrix4f root = scene.screenModel(node, width, height);
				// Sizes are fractions of the frame height.
				drawElement(batch, node, root, height, false, replayId);
			}
			batch.draw("billboards screen overlays", false);
		} finally {
			ScreenProjection.end();
		}
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

	// ------------------------------------------------------------------------------------------------
	// Element drawing
	// ------------------------------------------------------------------------------------------------

	/**
	 * How one pass over an element is drawn: normally, or as a single-colour silhouette (for shadows, outlines and
	 * glows) moved by an offset in the element's local frame.
	 *
	 * @param silhouetteColour ARGB colour of the silhouette (its alpha multiplies the element's own alpha)
	 */
	private record Style(boolean silhouette, int silhouetteColour, float offsetX, float offsetY, BlendMode blend) {
		static Style normal(BlendMode blend) {
			return new Style(false, 0, 0, 0, blend);
		}

		static Style silhouette(int colour, float offsetX, float offsetY) {
			return new Style(true, colour, offsetX, offsetY, BlendMode.NORMAL);
		}

		Matrix4f apply(Matrix4f root) {
			return this.offsetX == 0 && this.offsetY == 0 ? root : new Matrix4f(root).translate(this.offsetX, this.offsetY, 0);
		}

		/** The vertex colour for a colour the element would normally draw with. */
		int map(int colour, int tint) {
			if (!this.silhouette) {
				return ShapeTessellator.multiply(colour, tint);
			}
			int alpha = ((colour >>> 24) & 0xFF) * ((tint >>> 24) & 0xFF) / 255 * ((this.silhouetteColour >>> 24) & 0xFF) / 255;
			return alpha << 24 | (this.silhouetteColour & 0xFFFFFF);
		}
	}

	/** Taps around a circle, used to grow silhouettes into outlines, soft shadows and glows. */
	private static final int RING_TAPS = 12;

	/**
	 * @param unit size multiplier: 1 in the world (sizes are in blocks), the frame height on screen
	 */
	private static void drawElement(OverlayBatch batch, SceneFrame.Node node, Matrix4f root, float unit, boolean depthTest, @Nullable UUID replayId) {
		Element element = node.element();
		if (element.type == ElementType.GROUP) {
			return;
		}
		Effects effects = element.effects;

		if (effects.glow && effects.glowRadius > 0) {
			// Three soft rings, fainter further out.
			float radius = effects.glowRadius * unit;
			for (int ring = 3; ring >= 1; ring--) {
				int colour = scaleAlpha(effects.glowColor, 0.35 * (4 - ring) / 3.0);
				drawRing(batch, node, root, unit, depthTest, replayId, radius * ring / 3f, colour, 0);
			}
		}
		if (effects.shadow) {
			// The offset is right/down as seen on screen, so it doesn't turn with the element.
			float dx = effects.shadowOffsetX * unit;
			float dy = effects.shadowOffsetY * unit;
			Matrix4f shadowRoot = element.space == me.zhdg.billboards.model.Space.SCREEN
				? new Matrix4f().translate(dx, dy, 0).mul(root)
				: new Matrix4f().translate(dx, -dy, 0).mul(root);
			float softness = effects.shadowSoftness * unit;
			if (softness <= 0) {
				drawContent(batch, node, shadowRoot, unit, depthTest, replayId, Style.silhouette(effects.shadowColor, 0, 0));
			} else {
				// Centre plus a ring, each faint enough that where they all overlap the shadow reaches its full strength.
				int colour = splitAlpha(effects.shadowColor, RING_TAPS + 1);
				drawContent(batch, node, shadowRoot, unit, depthTest, replayId, Style.silhouette(colour, 0, 0));
				drawRing(batch, node, shadowRoot, unit, depthTest, replayId, softness, colour, 0);
			}
		}
		if (effects.outline && effects.outlineWidth > 0) {
			float width = effects.outlineWidth * unit;
			drawRing(batch, node, root, unit, depthTest, replayId, width, effects.outlineColor, 0);
			drawRing(batch, node, root, unit, depthTest, replayId, width / 2, effects.outlineColor, (float) Math.PI / RING_TAPS);
		}

		drawContent(batch, node, root, unit, depthTest, replayId, Style.normal(element.blend));
	}

	private static void drawRing(OverlayBatch batch, SceneFrame.Node node, Matrix4f root, float unit, boolean depthTest, @Nullable UUID replayId,
	                             float radius, int colour, float angleOffset) {
		for (int i = 0; i < RING_TAPS; i++) {
			double angle = angleOffset + 2 * Math.PI * i / RING_TAPS;
			Style style = Style.silhouette(colour, radius * (float) Math.cos(angle), radius * (float) Math.sin(angle));
			drawContent(batch, node, root, unit, depthTest, replayId, style);
		}
	}

	private static void drawContent(OverlayBatch batch, SceneFrame.Node node, Matrix4f root, float unit, boolean depthTest,
	                                @Nullable UUID replayId, Style style) {
		Matrix4f pose = style.apply(root);
		switch (node.element().type) {
			case IMAGE, VIDEO -> drawImage(batch, node, pose, unit, depthTest, replayId, style);
			case TEXT -> drawText(batch, node, pose, unit, depthTest, style);
			case SHAPE -> drawShape(batch, node, pose, unit, depthTest, style);
			case GROUP -> {
			}
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

	private static GpuSampler sampler(FilterMode mode) {
		return RenderSystem.getSamplerCache().getClampToEdge(mode);
	}

	private static void drawImage(OverlayBatch batch, SceneFrame.Node node, Matrix4f root, float unit, boolean depthTest,
	                              @Nullable UUID replayId, Style style) {
		Element element = node.element();
		ElementGeometry.Bounds bounds = ElementGeometry.localBounds(element, node.content(), unit);
		if (bounds == null) {
			return;
		}

		GpuTextureView view = null;
		if (replayId != null) {
			if (MediaPlayers.isPlayed(replayId, element)) {
				PlaybackTime time = PlaybackTime.current();
				view = time == null ? null : MediaPlayers.frame(replayId, element, time.tick());
			} else {
				ImageTextures.Entry texture = ImageTextures.get(replayId, element.assetId);
				view = texture == null ? null : texture.view();
			}
		}
		boolean missing = view == null;
		if (missing) {
			view = ImageTextures.placeholder().view();
		}

		ChannelValues content = node.content();
		float u0 = content.getFloat(Channel.CROP_LEFT);
		float v0 = content.getFloat(Channel.CROP_TOP);
		float u1 = content.getFloat(Channel.CROP_RIGHT);
		float v1 = content.getFloat(Channel.CROP_BOTTOM);
		float minX = bounds.minX();
		float minY = bounds.minY();
		float maxX = bounds.maxX();
		float maxY = bounds.maxY();
		if (missing) {
			// Tile the checkerboard rather than stretching two pixels across the whole quad.
			u0 = 0;
			v0 = 0;
			u1 = Math.max(1, element.width * 4);
			v1 = Math.max(1, element.height * 4);
		}

		// Blur (normal pass only): grow the quad by the radius so the blurred edge has room, in UV units too.
		float blurU = 0;
		float blurV = 0;
		OverlayPipelines.Variant variant = OverlayPipelines.Variant.of(style.silhouette(), false);
		if (!style.silhouette() && !missing && element.effects.blur > 0) {
			float radius = element.effects.blur * unit;
			float uPerUnit = (u1 - u0) / Math.max(1e-6f, maxX - minX);
			float vPerUnit = (v1 - v0) / Math.max(1e-6f, maxY - minY);
			blurU = radius * uPerUnit;
			blurV = radius * vPerUnit;
			minX -= radius;
			maxX += radius;
			minY -= radius;
			maxY += radius;
			u0 -= blurU;
			u1 += blurU;
			v0 -= blurV;
			v1 += blurV;
			variant = OverlayPipelines.Variant.BLUR;
		}

		int color = style.map(0xFFFFFFFF, color(node.appearance()));
		GpuSampler sampler = missing
			? RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)
			: sampler(element.pixelated ? FilterMode.NEAREST : FilterMode.LINEAR);
		VertexConsumer buffer = batch.begin(OverlayPipelines.get(depthTest, variant, style.blend()), view, sampler, blurU, blurV);
		buffer.addVertex(root, minX, maxY, 0).setUv(u0, v0).setColor(color);
		buffer.addVertex(root, minX, minY, 0).setUv(u0, v1).setColor(color);
		buffer.addVertex(root, maxX, minY, 0).setUv(u1, v1).setColor(color);
		buffer.addVertex(root, maxX, maxY, 0).setUv(u1, v0).setColor(color);
	}

	private static void drawShape(OverlayBatch batch, SceneFrame.Node node, Matrix4f root, float unit, boolean depthTest, Style style) {
		ElementGeometry.Bounds bounds = ElementGeometry.localBounds(node.element(), node.content(), unit);
		if (bounds == null) {
			return;
		}
		int tint = color(node.appearance());
		VertexConsumer buffer = batch.begin(OverlayPipelines.get(depthTest, OverlayPipelines.Variant.of(style.silhouette(), false), style.blend()),
			ImageTextures.white().view(), sampler(FilterMode.NEAREST));
		ShapeTessellator.draw(buffer, root, node.element(), bounds, unit, colour -> style.map(colour, tint));
	}

	private static void drawText(OverlayBatch batch, SceneFrame.Node node, Matrix4f root, float unit, boolean depthTest, Style style) {
		Element element = node.element();
		ChannelValues appearance = node.appearance();
		ElementGeometry.TextLayout layout = ElementGeometry.textLayout(element, node.content(), unit);
		if (layout == null) {
			return;
		}

		float pixel = layout.pixel();
		Matrix4f pose = new Matrix4f(root)
			.translate(-element.pivotX * layout.blockWidth() * pixel, element.pivotY * layout.blockHeight() * pixel, 0)
			.scale(pixel, -pixel, pixel); // glyph y grows downwards

		int color = style.map(0xFFFFFFFF, color(appearance));
		// Silhouettes are just the glyphs: no text shadow or background.
		boolean textShadow = element.textShadow && !style.silhouette();
		int background = style.silhouette() ? 0 : scaleAlpha(element.backgroundColor, appearance.get(Channel.OPACITY));

		if (layout.font() != null) {
			drawCustomFontText(batch, element, layout, pose, color, background, textShadow, depthTest, style);
			return;
		}

		if (((color >>> 24) & 0xFF) / 255f < MIN_TEXT_ALPHA) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		GpuSampler sampler = sampler(FilterMode.NEAREST);
		String[] lines = layout.lines();
		for (int i = 0; i < lines.length; i++) {
			float x = alignedX(element, layout, i);
			Font.PreparedText prepared = font.prepareText(lines[i], x, i * layout.lineHeight(), color, textShadow, background);
			prepared.visit(new Font.GlyphVisitor() {
				@Override
				public void acceptRenderable(TextRenderable renderable) {
					boolean grayscale = renderable.guiPipeline() == RenderPipelines.GUI_TEXT_GRAYSCALE;
					VertexConsumer buffer = batch.begin(
						OverlayPipelines.get(depthTest, OverlayPipelines.Variant.of(style.silhouette(), grayscale), style.blend()),
						renderable.textureView(), sampler);
					// "Flat": no z offsets between shadow, glyph and background. Vanilla relies on depth testing to
					// keep the glyph above its shadow; overlays are drawn strictly in order instead, and the offsets
					// would push glyphs out of the screen pass's depth range.
					renderable.render(pose, buffer, LightCoordsUtil.FULL_BRIGHT, true);
				}
			});
		}
	}

	/** Text in a TTF/OTF font: background, then shadow, then glyphs, all from the font's glyph atlas. */
	private static void drawCustomFontText(OverlayBatch batch, Element element, ElementGeometry.TextLayout layout, Matrix4f pose,
	                                       int color, int background, boolean textShadow, boolean depthTest, Style style) {
		CustomFont font = layout.font();
		// Same proportions as Minecraft's font: one "pixel" of a 9-pixel line.
		float unitPixel = layout.lineHeight() / 9f;
		String[] lines = layout.lines();

		if ((background >>> 24) != 0) {
			VertexConsumer buffer = batch.begin(OverlayPipelines.get(depthTest, OverlayPipelines.Variant.NORMAL, style.blend()),
				ImageTextures.white().view(), sampler(FilterMode.NEAREST));
			for (int i = 0; i < lines.length; i++) {
				float x = alignedX(element, layout, i);
				float top = i * layout.lineHeight();
				quad(buffer, pose, x - unitPixel, top - unitPixel, x + layout.lineWidths()[i] + unitPixel, top + layout.lineHeight(), 0.5f, 0.5f, 0.5f, 0.5f, background);
			}
		}

		// Look glyphs up (rasterizing new ones) before taking the texture, so it is uploaded with them.
		for (String line : lines) {
			font.width(line);
		}
		VertexConsumer buffer = batch.begin(OverlayPipelines.get(depthTest, OverlayPipelines.Variant.of(style.silhouette(), false), style.blend()),
			font.textureView(), sampler(FilterMode.LINEAR));
		if (textShadow) {
			int shadow = (color & 0xFF000000) | ((((color >> 16) & 0xFF) / 4) << 16) | ((((color >> 8) & 0xFF) / 4) << 8) | ((color & 0xFF) / 4);
			drawGlyphs(buffer, font, element, layout, pose, unitPixel, shadow);
		}
		drawGlyphs(buffer, font, element, layout, pose, 0, color);
	}

	private static void drawGlyphs(VertexConsumer buffer, CustomFont font, Element element, ElementGeometry.TextLayout layout, Matrix4f pose,
	                               float offset, int color) {
		String[] lines = layout.lines();
		for (int i = 0; i < lines.length; i++) {
			float penX = alignedX(element, layout, i) + offset;
			float baseline = i * layout.lineHeight() + font.ascent() + offset;
			String line = lines[i];
			for (int c = 0; c < line.length(); ) {
				int codePoint = line.codePointAt(c);
				CustomFont.Glyph glyph = font.glyph(codePoint);
				if (glyph.visible()) {
					quad(buffer, pose, penX + glyph.x0(), baseline + glyph.y0(), penX + glyph.x1(), baseline + glyph.y1(),
						glyph.u0(), glyph.v0(), glyph.u1(), glyph.v1(), color);
				}
				penX += glyph.advance();
				c += Character.charCount(codePoint);
			}
		}
	}

	/** A quad in glyph space (y down): (x0, y0) top-left, (x1, y1) bottom-right. */
	private static void quad(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1,
	                         float u0, float v0, float u1, float v1, int color) {
		buffer.addVertex(pose, x0, y0, 0).setUv(u0, v0).setColor(color);
		buffer.addVertex(pose, x0, y1, 0).setUv(u0, v1).setColor(color);
		buffer.addVertex(pose, x1, y1, 0).setUv(u1, v1).setColor(color);
		buffer.addVertex(pose, x1, y0, 0).setUv(u1, v0).setColor(color);
	}

	private static float alignedX(Element element, ElementGeometry.TextLayout layout, int line) {
		float lineWidth = layout.lineWidths()[line];
		return switch (element.align) {
			case LEFT -> 0;
			case CENTER -> (layout.blockWidth() - lineWidth) / 2f;
			case RIGHT -> layout.blockWidth() - lineWidth;
		};
	}

	private static int scaleAlpha(int argb, double opacity) {
		int alpha = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, opacity)));
		return alpha << 24 | (argb & 0xFFFFFF);
	}

	/** An alpha for each of {@code taps} overlapping copies, so that together they reach the colour's own alpha. */
	private static int splitAlpha(int argb, int taps) {
		double alpha = ((argb >>> 24) & 0xFF) / 255.0;
		double each = 1 - Math.pow(1 - Math.min(alpha, 0.999), 1.0 / taps);
		return (int) Math.round(each * 255) << 24 | (argb & 0xFFFFFF);
	}
}
