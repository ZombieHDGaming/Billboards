package me.zhdg.billboards.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.moulberry.flashback.Flashback;
import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.model.BlendMode;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Effects;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Lighting;
import me.zhdg.billboards.project.ProjectManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Draws overlays into the world. Placement comes from {@link SceneFrame} / {@link ElementGeometry}; this class turns
 * it into quads.
 */
public final class OverlayRenderer {

	/** Below this opacity (as a byte) vanilla text rendering treats the colour as opaque, so skip the element. */
	private static final float MIN_TEXT_ALPHA = 4f / 255f;

	private OverlayRenderer() {
	}

	/**
	 * The camera of a rendered level frame, for billboards, picking and gizmos.
	 *
	 * @param xRot camera pitch and {@code yRot} camera yaw, in degrees (as on Minecraft's camera)
	 */
	public record SceneCamera(Vec3 position, Quaternionf orientation, Matrix4f viewRotation, Matrix4f projection, float xRot, float yRot) {
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
	// Pass
	// ------------------------------------------------------------------------------------------------

	/** Called at the end of level rendering with the level projection still bound. */
	public static void renderWorld(CameraRenderState cameraState) {
		SceneCamera camera = new SceneCamera(cameraState.pos, new Quaternionf(cameraState.orientation),
			new Matrix4f(cameraState.viewRotationMatrix), new Matrix4f(cameraState.projectionMatrix), cameraState.xRot, cameraState.yRot);
		lastCamera = camera;

		SceneFrame scene = SceneFrame.current();
		if (scene == null) {
			return;
		}
		List<SceneFrame.Node> nodes = scene.drawables();
		if (nodes.isEmpty()) {
			return;
		}

		Vec3 cameraPos = camera.position();
		// Depth-tested elements first, far to near, then see-through elements by layer.
		nodes.sort(Comparator
			.comparingInt((SceneFrame.Node n) -> n.element().depthTest ? 0 : 1)
			.thenComparingInt(n -> n.element().depthTest ? 0 : scene.rootLayer(n))
			.thenComparingDouble(n -> {
				if (!n.element().depthTest) {
					return 0;
				}
				Vec3 position = scene.worldPosition(n, camera);
				return position == null ? 0 : -position.distanceToSqr(cameraPos);
			}));

		UUID replayId = ProjectManager.currentReplayId();
		try (OverlayBatch batch = new OverlayBatch()) {
			for (SceneFrame.Node node : nodes) {
				Matrix4f model = scene.worldModel(node, camera);
				if (model == null) {
					continue;
				}
				Vector3f offset = model.getTranslation(new Vector3f());
				int light = light(node.element(), cameraPos.add(offset.x, offset.y, offset.z));
				Matrix4f root = new Matrix4f(camera.viewRotation()).mul(model);
				drawElement(new Target(batch, node, replayId, light), root);
			}
			batch.draw("billboards overlays");
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

	/**
	 * The packed light an element is drawn with, or -1 for full brightness (drawn without the lightmap).
	 * World light is sampled like an entity's: block and sky light of the block the element's pivot is in.
	 */
	public static int light(Element element, Vec3 position) {
		return switch (element.lighting) {
			case FULL_BRIGHT -> -1;
			case CUSTOM -> LightCoordsUtil.pack(element.blockLight, element.skyLight);
			case WORLD -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level == null) {
					yield -1;
				}
				BlockPos pos = BlockPos.containing(position);
				yield LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
			}
		};
	}

	// ------------------------------------------------------------------------------------------------
	// Element drawing
	// ------------------------------------------------------------------------------------------------

	/** What is being drawn this pass: the element, how it is lit ({@code light} -1 for full bright), and where to. */
	private record Target(OverlayBatch batch, SceneFrame.Node node, @Nullable UUID replayId, int light) {
		Element element() {
			return this.node.element();
		}

		boolean lit() {
			return this.light >= 0;
		}

		int vertexLight() {
			return this.light >= 0 ? this.light : LightCoordsUtil.FULL_BRIGHT;
		}

		com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline(OverlayPipelines.Variant variant, BlendMode blend) {
			return OverlayPipelines.get(this.element().depthTest, this.lit(), variant, blend);
		}

		VertexConsumer begin(OverlayPipelines.Variant variant, BlendMode blend, GpuTextureView texture, GpuSampler sampler) {
			return this.batch.begin(this.pipeline(variant, blend), texture, sampler, this.vertexLight());
		}
	}

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

	private static void drawElement(Target target, Matrix4f root) {
		Element element = target.element();
		if (element.type == ElementType.GROUP) {
			return;
		}
		Effects effects = element.effects;

		if (effects.glow && effects.glowRadius > 0) {
			// Three soft rings, fainter further out.
			float radius = effects.glowRadius;
			for (int ring = 3; ring >= 1; ring--) {
				int colour = scaleAlpha(effects.glowColor, 0.35 * (4 - ring) / 3.0);
				drawRing(target, root, radius * ring / 3f, colour, 0);
			}
		}
		if (effects.shadow) {
			// The offset is right/down as seen from the camera, so it doesn't turn with the element.
			Matrix4f shadowRoot = new Matrix4f().translate(effects.shadowOffsetX, -effects.shadowOffsetY, 0).mul(root);
			float softness = effects.shadowSoftness;
			if (softness <= 0) {
				drawContent(target, shadowRoot, Style.silhouette(effects.shadowColor, 0, 0));
			} else {
				// Centre plus a ring, each faint enough that where they all overlap the shadow reaches its full strength.
				int colour = splitAlpha(effects.shadowColor, RING_TAPS + 1);
				drawContent(target, shadowRoot, Style.silhouette(colour, 0, 0));
				drawRing(target, shadowRoot, softness, colour, 0);
			}
		}
		if (effects.outline && effects.outlineWidth > 0) {
			float width = effects.outlineWidth;
			drawRing(target, root, width, effects.outlineColor, 0);
			drawRing(target, root, width / 2, effects.outlineColor, (float) Math.PI / RING_TAPS);
		}

		drawContent(target, root, Style.normal(element.blend));
	}

	private static void drawRing(Target target, Matrix4f root, float radius, int colour, float angleOffset) {
		for (int i = 0; i < RING_TAPS; i++) {
			double angle = angleOffset + 2 * Math.PI * i / RING_TAPS;
			Style style = Style.silhouette(colour, radius * (float) Math.cos(angle), radius * (float) Math.sin(angle));
			drawContent(target, root, style);
		}
	}

	private static void drawContent(Target target, Matrix4f root, Style style) {
		Matrix4f pose = style.apply(root);
		switch (target.element().type) {
			case IMAGE, VIDEO -> drawImage(target, pose, style);
			case TEXT -> drawText(target, pose, style);
			case SHAPE -> drawShape(target, pose, style);
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

	private static void drawImage(Target target, Matrix4f root, Style style) {
		SceneFrame.Node node = target.node();
		Element element = node.element();
		UUID replayId = target.replayId();
		ElementGeometry.Bounds bounds = ElementGeometry.localBounds(element, node.content());
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
			float radius = element.effects.blur;
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
		VertexConsumer buffer = target.batch().begin(target.pipeline(variant, style.blend()), view, sampler, blurU, blurV, target.vertexLight());
		buffer.addVertex(root, minX, maxY, 0).setUv(u0, v0).setColor(color);
		buffer.addVertex(root, minX, minY, 0).setUv(u0, v1).setColor(color);
		buffer.addVertex(root, maxX, minY, 0).setUv(u1, v1).setColor(color);
		buffer.addVertex(root, maxX, maxY, 0).setUv(u1, v0).setColor(color);
	}

	private static void drawShape(Target target, Matrix4f root, Style style) {
		SceneFrame.Node node = target.node();
		ElementGeometry.Bounds bounds = ElementGeometry.localBounds(node.element(), node.content());
		if (bounds == null) {
			return;
		}
		int tint = color(node.appearance());
		VertexConsumer buffer = target.begin(OverlayPipelines.Variant.of(style.silhouette(), false), style.blend(),
			ImageTextures.white().view(), sampler(FilterMode.NEAREST));
		ShapeTessellator.draw(buffer, root, node.element(), bounds, colour -> style.map(colour, tint));
	}

	/**
	 * Text drawn like a vanilla text display: font pixels of {@link ElementGeometry#TEXT_PIXEL} blocks, lines
	 * {@link ElementGeometry#LINE_HEIGHT} pixels apart, the background one pixel around the text block, and
	 * formatting colours taking over from the text colour (the opacity still applies).
	 */
	private static void drawText(Target target, Matrix4f root, Style style) {
		Element element = target.element();
		ChannelValues appearance = target.node().appearance();
		ElementGeometry.TextLayout layout = ElementGeometry.textLayout(element, target.node().content());
		if (layout == null) {
			return;
		}

		float pixel = ElementGeometry.TEXT_PIXEL;
		// Font pixels with y growing downwards, the pivot at the origin.
		Matrix4f pose = new Matrix4f(root)
			.scale(pixel, -pixel, pixel)
			.translate(-layout.pivotX(element), -layout.pivotY(element), 0);

		int color = style.map(0xFFFFFFFF, color(appearance));
		if (((color >>> 24) & 0xFF) / 255f < MIN_TEXT_ALPHA) {
			return;
		}

		if (!style.silhouette()) {
			// Silhouettes are just the glyphs: no background or text shadow.
			int background = scaleAlpha(element.backgroundColor, appearance.get(Channel.OPACITY));
			if ((background >>> 24) != 0) {
				VertexConsumer buffer = target.begin(OverlayPipelines.Variant.NORMAL, style.blend(), ImageTextures.white().view(), sampler(FilterMode.NEAREST));
				quad(buffer, pose, -1, -1, layout.width(), layout.height(), 0.5f, 0.5f, 0.5f, 0.5f, background);
			}
		}
		boolean textShadow = element.textShadow && !style.silhouette();

		if (layout.font() != null) {
			drawCustomFontText(target, layout, pose, color, textShadow, style);
			return;
		}

		Font font = Minecraft.getInstance().font;
		GpuSampler sampler = sampler(FilterMode.NEAREST);
		List<FormattedCharSequence> lines = layout.lines();
		for (int i = 0; i < lines.size(); i++) {
			FormattedCharSequence line = style.silhouette() ? ElementGeometry.uncoloured(lines.get(i)) : lines.get(i);
			Font.PreparedText prepared = font.prepareText(line, layout.lineX(i, element.align), i * ElementGeometry.LINE_HEIGHT, color, textShadow, false, 0);
			prepared.visit(new Font.GlyphVisitor() {
				@Override
				public void acceptRenderable(TextRenderable renderable) {
					boolean grayscale = renderable.guiPipeline() == RenderPipelines.GUI_TEXT_GRAYSCALE;
					VertexConsumer buffer = target.begin(OverlayPipelines.Variant.of(style.silhouette(), grayscale), style.blend(),
						renderable.textureView(), sampler);
					// "Flat": no z offsets between shadow and glyph. Vanilla relies on depth testing to keep the glyph
					// above its shadow; overlays don't write depth and are drawn strictly in order instead.
					renderable.render(pose, buffer, target.vertexLight(), true);
				}
			});
		}
	}

	/** Text in a TTF/OTF font, from the font's glyph atlas: shadow, then glyphs. */
	private static void drawCustomFontText(Target target, ElementGeometry.TextLayout layout, Matrix4f pose, int color, boolean textShadow, Style style) {
		CustomFont font = layout.font();
		VertexConsumer buffer = target.begin(OverlayPipelines.Variant.of(style.silhouette(), false), style.blend(),
			font.textureView(), sampler(FilterMode.LINEAR));
		if (textShadow) {
			drawCustomGlyphs(buffer, target.element(), layout, pose, 1, color, true);
		}
		drawCustomGlyphs(buffer, target.element(), layout, pose, 0, color, false);
	}

	private static void drawCustomGlyphs(VertexConsumer buffer, Element element, ElementGeometry.TextLayout layout, Matrix4f pose,
	                                     float offset, int color, boolean shadow) {
		CustomFont font = layout.font();
		float scale = layout.fontScale();
		List<FormattedCharSequence> lines = layout.lines();
		for (int i = 0; i < lines.size(); i++) {
			float left = layout.lineX(i, element.align) + offset;
			float baseline = i * ElementGeometry.LINE_HEIGHT + font.ascent() * scale + offset;
			float[] pen = {0};
			lines.get(i).accept((index, charStyle, codePoint) -> {
				CustomFont.Glyph glyph = font.glyph(codePoint);
				if (glyph.visible()) {
					TextColor styleColour = charStyle.getColor();
					int glyphColour = styleColour == null ? color : (color & 0xFF000000) | (styleColour.getValue() & 0xFFFFFF);
					if (shadow) {
						glyphColour = (glyphColour & 0xFF000000) | ((((glyphColour >> 16) & 0xFF) / 4) << 16)
							| ((((glyphColour >> 8) & 0xFF) / 4) << 8) | ((glyphColour & 0xFF) / 4);
					}
					float x = left + pen[0] * scale;
					quad(buffer, pose, x + glyph.x0() * scale, baseline + glyph.y0() * scale, x + glyph.x1() * scale, baseline + glyph.y1() * scale,
						glyph.u0(), glyph.v0(), glyph.u1(), glyph.v1(), glyphColour);
				}
				pen[0] += glyph.advance();
				return true;
			});
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
