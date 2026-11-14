package me.zhdg.billboards.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.project.AssetStore;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GPU textures for image assets, loaded on first use and kept until the replay changes. Decodes with ImageIO so
 * PNG, JPEG, BMP and the first frame of GIFs all work. Render thread only.
 */
public final class ImageTextures {

	public record Entry(DynamicTexture texture, int width, int height) {
		public GpuTextureView view() {
			return this.texture.getTextureView();
		}
	}

	private static final Map<String, Entry> LOADED = new HashMap<>();
	/** Assets that failed to load, so we don't retry (and log) every frame. */
	private static final Map<String, Boolean> FAILED = new HashMap<>();
	private static @Nullable UUID loadedFor = null;
	private static @Nullable Entry placeholder = null;

	private ImageTextures() {
	}

	/** The texture for an asset, or null if it is missing or unreadable (callers draw {@link #placeholder()}). */
	public static @Nullable Entry get(UUID replayId, @Nullable String assetId) {
		if (!replayId.equals(loadedFor)) {
			clear();
			loadedFor = replayId;
		}
		if (assetId == null || FAILED.containsKey(assetId)) {
			return null;
		}
		Entry entry = LOADED.get(assetId);
		if (entry != null) {
			return entry;
		}
		try {
			Path path = AssetStore.resolve(replayId, assetId);
			if (!Files.exists(path)) {
				throw new java.io.FileNotFoundException(path.toString());
			}
			BufferedImage image = ImageIO.read(path.toFile());
			if (image == null) {
				throw new java.io.IOException("Unsupported image format");
			}
			entry = upload("billboards image " + assetId, image);
			LOADED.put(assetId, entry);
			return entry;
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to load image asset {}", assetId, e);
			FAILED.put(assetId, true);
			return null;
		}
	}

	/** Image dimensions without uploading, for sizing a newly imported image. */
	public static int @Nullable [] readSize(Path path) {
		try {
			BufferedImage image = ImageIO.read(path.toFile());
			return image == null ? null : new int[]{image.getWidth(), image.getHeight()};
		} catch (Exception e) {
			return null;
		}
	}

	/** A magenta/black checkerboard shown in place of missing images. */
	public static Entry placeholder() {
		if (placeholder == null) {
			BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, 0xFFFF00FF);
			image.setRGB(1, 1, 0xFFFF00FF);
			image.setRGB(1, 0, 0xFF000000);
			image.setRGB(0, 1, 0xFF000000);
			placeholder = upload("billboards missing image", image);
		}
		return placeholder;
	}

	public static void clear() {
		LOADED.values().forEach(entry -> entry.texture().close());
		LOADED.clear();
		FAILED.clear();
		loadedFor = null;
	}

	/** Forget a failed load so the next frame retries, e.g. after the user relinks or re-imports a file. */
	public static void retry(String assetId) {
		FAILED.remove(assetId);
	}

	private static Entry upload(String label, BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		NativeImage nativeImage = new NativeImage(width, height, false);
		int[] row = new int[width];
		for (int y = 0; y < height; y++) {
			image.getRGB(0, y, width, 1, row, 0, width);
			for (int x = 0; x < width; x++) {
				nativeImage.setPixel(x, y, row[x]); // setPixel takes ARGB
			}
		}
		return new Entry(new DynamicTexture(() -> label, nativeImage), width, height);
	}
}
