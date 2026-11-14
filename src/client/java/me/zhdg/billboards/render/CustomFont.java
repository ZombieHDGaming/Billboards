package me.zhdg.billboards.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.project.AssetStore;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A TTF/OTF font from the project's assets, rasterized with Java's font engine into a glyph atlas on demand.
 * Independent of Minecraft's font system, so any font file works. Render thread only.
 * <p>
 * Glyph metrics are in raster pixels (the font is rasterized at {@link #RASTER_SIZE} px), with y growing downwards
 * from the baseline.
 */
public final class CustomFont {

	public static final float RASTER_SIZE = 64f;
	private static final int ATLAS_SIZE = 1024;
	private static final int PADDING = 2;

	/** A glyph's atlas UVs, its quad relative to the pen position on the baseline, and how far it advances the pen. */
	public record Glyph(float u0, float v0, float u1, float v1, float x0, float y0, float x1, float y1, float advance, boolean visible) {
	}

	private static final Map<String, CustomFont> LOADED = new HashMap<>();
	private static final Set<String> FAILED = new HashSet<>();
	private static @Nullable UUID loadedFor = null;

	private final Font font;
	private final FontRenderContext context = new FontRenderContext(null, true, true);
	private final float ascent;
	private final float lineHeight;
	private final NativeImage atlas;
	private final DynamicTexture texture;
	private final Map<Integer, Glyph> glyphs = new HashMap<>();
	private int penX = PADDING;
	private int penY = PADDING;
	private int rowHeight = 0;
	private boolean dirty = false;

	private CustomFont(Font font, String label) {
		this.font = font;
		LineMetrics metrics = font.getLineMetrics("Ag", this.context);
		this.ascent = metrics.getAscent();
		this.lineHeight = metrics.getAscent() + metrics.getDescent();
		this.atlas = new NativeImage(ATLAS_SIZE, ATLAS_SIZE, true);
		this.texture = new DynamicTexture(() -> "billboards font " + label, this.atlas);
	}

	/** The font for an asset, or null if it is missing or isn't a font Java can read (callers fall back to Minecraft's font). */
	public static @Nullable CustomFont get(@Nullable UUID replayId, @Nullable String assetId) {
		if (replayId == null || assetId == null) {
			return null;
		}
		if (!replayId.equals(loadedFor)) {
			clear();
			loadedFor = replayId;
		}
		if (FAILED.contains(assetId)) {
			return null;
		}
		CustomFont font = LOADED.get(assetId);
		if (font != null) {
			return font;
		}
		try {
			Path path = AssetStore.resolve(replayId, assetId);
			if (!Files.exists(path)) {
				throw new java.io.FileNotFoundException(path.toString());
			}
			Font awt = Font.createFont(Font.TRUETYPE_FONT, path.toFile()).deriveFont(RASTER_SIZE);
			font = new CustomFont(awt, assetId);
			LOADED.put(assetId, font);
			return font;
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to load font asset {}", assetId, e);
			FAILED.add(assetId);
			return null;
		}
	}

	/** Checks a font file can be used before importing it. */
	public static boolean isReadable(Path path) {
		try {
			Font.createFont(Font.TRUETYPE_FONT, path.toFile());
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public static void clear() {
		LOADED.values().forEach(font -> font.texture.close());
		LOADED.clear();
		FAILED.clear();
		loadedFor = null;
	}

	public static void retry(String assetId) {
		FAILED.remove(assetId);
	}

	public String familyName() {
		return this.font.getFamily();
	}

	public float ascent() {
		return this.ascent;
	}

	public float lineHeight() {
		return this.lineHeight;
	}

	public float width(String text) {
		float width = 0;
		for (int i = 0; i < text.length(); ) {
			int codePoint = text.codePointAt(i);
			width += this.glyph(codePoint).advance();
			i += Character.charCount(codePoint);
		}
		return width;
	}

	/** The atlas texture, uploading any glyphs added since the last call. */
	public GpuTextureView textureView() {
		if (this.dirty) {
			this.texture.upload();
			this.dirty = false;
		}
		return this.texture.getTextureView();
	}

	public Glyph glyph(int codePoint) {
		Glyph glyph = this.glyphs.get(codePoint);
		if (glyph == null) {
			glyph = this.rasterize(codePoint);
			this.glyphs.put(codePoint, glyph);
		}
		return glyph;
	}

	private Glyph rasterize(int codePoint) {
		GlyphVector vector = this.font.createGlyphVector(this.context, new String(Character.toChars(codePoint)));
		float advance = vector.getGlyphMetrics(0).getAdvance();
		Rectangle bounds = vector.getPixelBounds(this.context, 0, 0);
		if (bounds.width <= 0 || bounds.height <= 0) {
			return new Glyph(0, 0, 0, 0, 0, 0, 0, 0, advance, false);
		}

		int width = bounds.width + 2;
		int height = bounds.height + 2;
		if (width + PADDING > ATLAS_SIZE || height + PADDING > ATLAS_SIZE) {
			return new Glyph(0, 0, 0, 0, 0, 0, 0, 0, advance, false);
		}
		if (this.penX + width + PADDING > ATLAS_SIZE) {
			this.penX = PADDING;
			this.penY += this.rowHeight + PADDING;
			this.rowHeight = 0;
		}
		if (this.penY + height + PADDING > ATLAS_SIZE) {
			// Atlas full: start again. Glyphs are re-rasterized as they are used.
			this.glyphs.clear();
			this.atlas.fillRect(0, 0, ATLAS_SIZE, ATLAS_SIZE, 0);
			this.penX = PADDING;
			this.penY = PADDING;
			this.rowHeight = 0;
		}

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		graphics.setColor(java.awt.Color.WHITE);
		graphics.drawGlyphVector(vector, 1 - bounds.x, 1 - bounds.y);
		graphics.dispose();

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int alpha = image.getRGB(x, y) >>> 24;
				// White with coverage in alpha; the vertex colour supplies the text colour.
				this.atlas.setPixel(this.penX + x, this.penY + y, alpha << 24 | 0xFFFFFF);
			}
		}

		Glyph glyph = new Glyph(
			this.penX / (float) ATLAS_SIZE, this.penY / (float) ATLAS_SIZE,
			(this.penX + width) / (float) ATLAS_SIZE, (this.penY + height) / (float) ATLAS_SIZE,
			bounds.x - 1, bounds.y - 1, bounds.x - 1 + width, bounds.y - 1 + height, advance, true);
		this.penX += width + PADDING;
		this.rowHeight = Math.max(this.rowHeight, height);
		this.dirty = true;
		return glyph;
	}
}
