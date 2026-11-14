package me.zhdg.billboards.test;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.BlendMode;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.ShapeKind;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.model.TextAlign;
import me.zhdg.billboards.project.AssetStore;
import me.zhdg.billboards.project.ProjectManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Milestone 5: blend modes and effects, checked against exact pixel colours. */
final class EffectsChecks {

	static final int BACKDROP = 0x404040;
	static final int SHADOW = 0xC00000;
	static final int OUTLINE = 0x00C000;
	/** Where each blend mode's square overlaps the backdrop, in frame fractions: {x, y}. */
	static final float[][] OVERLAPS = {{0.91f, 0.1f}, {0.91f, 0.22f}, {0.91f, 0.34f}};

	private EffectsChecks() {
	}

	static void run(ClientGameTestContext context) {
		Path image;
		try {
			image = Files.createTempDirectory("billboards-fx").resolve("sharp.png");
			BufferedImage sharp = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < 32; y++) {
				for (int x = 0; x < 32; x++) {
					sharp.setRGB(x, y, x < 16 ? 0xFFFFFFFF : 0xFF000000);
				}
			}
			ImageIO.write(sharp, "png", image.toFile());
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}

		context.runOnClient(minecraft -> {
			BillboardProject project = ProjectManager.getCurrent();
			UUID replayId = ProjectManager.currentReplayId();

			// Blend modes: a square of each mode half over an opaque backdrop.
			BlendMode[] modes = {BlendMode.ADDITIVE, BlendMode.MULTIPLY, BlendMode.SCREEN};
			int[] colours = {0x202020, 0x808080, 0x808080};
			for (int i = 0; i < modes.length; i++) {
				// Positions are fractions of the frame's width, sizes of its height: in a 16:9 frame the backdrop spans
				// about x 0.82-0.94 and the square x 0.89-0.95, so they overlap around x 0.91.
				Element backdrop = square("Backdrop " + i, BACKDROP, 0.88f, OVERLAPS[i][1], 0.2f, 10);
				Element top = square(modes[i].displayName + " square", colours[i], 0.92f, OVERLAPS[i][1], 0.06f, 11);
				top.blend = modes[i];
				project.elements.add(backdrop);
				project.elements.add(top);
			}

			// Hard drop shadow, offset right and down.
			Element shadowed = rectangle("Shadowed square", 0x2060A0, 0.88f, 0.48f, 0.06f, 0.06f, 12);
			shadowed.effects.shadow = true;
			shadowed.effects.shadowColor = 0xFF000000 | SHADOW;
			shadowed.effects.shadowOffsetX = 0.02f;
			shadowed.effects.shadowOffsetY = 0.02f;
			shadowed.effects.shadowSoftness = 0;
			project.elements.add(shadowed);

			// Outlined text, and a glow.
			Element text = new Element();
			text.name = "Outlined text";
			text.type = ElementType.TEXT;
			text.space = Space.SCREEN;
			text.content.text = "FX";
			text.fontSize = 0.1f;
			text.align = TextAlign.CENTER;
			text.textShadow = false;
			text.transform.set(Channel.X, 0.42);
			text.transform.set(Channel.Y, 0.4);
			text.effects.outline = true;
			text.effects.outlineColor = 0xFF000000 | OUTLINE;
			text.effects.outlineWidth = 0.006f;
			text.effects.glow = true;
			text.effects.glowColor = 0xFFFFD040;
			text.effects.glowRadius = 0.02f;
			text.layer = 12;
			project.elements.add(text);

			// A blurred black/white image.
			Element blurred = new Element();
			blurred.name = "Blurred image";
			blurred.type = ElementType.IMAGE;
			blurred.space = Space.SCREEN;
			try {
				blurred.assetId = AssetStore.importFile(replayId, project, image);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			blurred.width = 0.12f;
			blurred.height = 0.12f;
			blurred.transform.set(Channel.X, 0.6);
			blurred.transform.set(Channel.Y, 0.4);
			blurred.effects.blur = 0.02f;
			blurred.layer = 12;
			project.elements.add(blurred);
			project.markDirty();
		});

		BufferedImage frame = screenshotAt(context, 45, "effects");
		int[] expected = {0x606060, 0x202020, 0x9F9F9F};
		for (int i = 0; i < OVERLAPS.length; i++) {
			int x = Math.round(OVERLAPS[i][0] * frame.getWidth());
			int y = Math.round(OVERLAPS[i][1] * frame.getHeight());
			int actual = frame.getRGB(x, y) & 0xFFFFFF;
			log("Blend " + i + " overlap colour: #" + Integer.toHexString(actual) + " (expected #" + Integer.toHexString(expected[i]) + ")");
			check(close(actual, expected[i], 3), "blend mode " + i + " should give #" + Integer.toHexString(expected[i]));
		}

		long shadowPixels = LowerThirdChecks.countPixels(frame, SHADOW);
		long outlinePixels = LowerThirdChecks.countPixels(frame, OUTLINE);
		long blurGreys = countGreys(frame, 0.6f - 0.08f, 0.4f - 0.08f, 0.6f + 0.08f, 0.4f + 0.08f);
		log("Shadow pixels " + shadowPixels + ", outline pixels " + outlinePixels + ", blurred mid-grey pixels " + blurGreys);
		check(shadowPixels > 20, "the drop shadow should show beside the square");
		check(outlinePixels > 20, "the outline should show around the text");
		check(blurGreys > 20, "the blurred edge should have in-between greys");
		ViewportChecks.desktopScreenshot(context, "desktop-effects");
	}

	private static Element square(String name, int rgb, float x, float y, float width, int layer) {
		return rectangle(name, rgb, x, y, width, 0.08f, layer);
	}

	private static Element rectangle(String name, int rgb, float x, float y, float width, float height, int layer) {
		Element element = new Element();
		element.name = name;
		element.type = ElementType.SHAPE;
		element.space = Space.SCREEN;
		element.shapeKind = ShapeKind.RECTANGLE;
		element.fillColor = 0xFF000000 | rgb;
		element.width = width;
		element.height = height;
		element.layer = layer;
		element.transform.set(Channel.X, x);
		element.transform.set(Channel.Y, y);
		return element;
	}

	private static long countGreys(BufferedImage image, float x0, float y0, float x1, float y1) {
		long count = 0;
		for (int y = Math.round(y0 * image.getHeight()); y < Math.round(y1 * image.getHeight()); y++) {
			for (int x = Math.round(x0 * image.getWidth()); x < Math.round(x1 * image.getWidth()); x++) {
				int rgb = image.getRGB(x, y);
				int r = (rgb >> 16) & 0xFF;
				int g = (rgb >> 8) & 0xFF;
				int b = rgb & 0xFF;
				if (Math.abs(r - g) < 4 && Math.abs(g - b) < 4 && r > 60 && r < 195) {
					count++;
				}
			}
		}
		return count;
	}

	private static boolean close(int a, int b, int tolerance) {
		for (int shift = 0; shift <= 16; shift += 8) {
			if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > tolerance) {
				return false;
			}
		}
		return true;
	}

	private static BufferedImage screenshotAt(ClientGameTestContext context, int tick, String name) {
		context.runOnClient(minecraft -> {
			ReplayServer replayServer = Flashback.getReplayServer();
			replayServer.replayPaused = true;
			replayServer.goToReplayTick(tick);
		});
		context.waitFor(minecraft -> Flashback.getReplayServer().getReplayTick() == tick, 20 * 30);
		context.waitTicks(10);
		try {
			return ImageIO.read(context.takeScreenshot(name).toFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void log(String message) {
		System.out.println("[Billboards test] " + message);
	}
}
