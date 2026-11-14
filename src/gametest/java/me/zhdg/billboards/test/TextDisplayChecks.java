package me.zhdg.billboards.test;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Lighting;
import me.zhdg.billboards.model.ShapeKind;
import me.zhdg.billboards.model.TextFormat;
import me.zhdg.billboards.project.ProjectManager;
import me.zhdg.billboards.render.ElementGeometry;
import me.zhdg.billboards.render.OverlayRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Text billboards behave like vanilla text displays: same size, line spacing, background, wrapping, formatting codes
 * and JSON text, billboard modes and world lighting.
 */
final class TextDisplayChecks {

	/** {@code &c} (red) and the "gold" JSON colour, exactly as Minecraft defines them. */
	static final int RED = 0xFF5555;
	static final int GOLD = 0xFFAA00;
	/** A MiniMessage hex colour. */
	static final int MINI = 0x2266EE;

	private TextDisplayChecks() {
	}

	static void run(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			checkLayout(minecraft.font);
			checkBillboards();
		});

		List<Element> added = new ArrayList<>();
		context.runOnClient(minecraft -> setUp(added));
		BufferedImage frame = screenshotAt(context, 45, "text-display");
		try {
			long red = LowerThirdChecks.countPixels(frame, RED);
			long gold = LowerThirdChecks.countPixels(frame, GOLD);
			long mini = LowerThirdChecks.countPixels(frame, MINI);
			log("Formatted text pixels: red (&c) " + red + ", gold (JSON) " + gold + ", MiniMessage #" + Integer.toHexString(MINI) + " " + mini);
			check(red > 30, "&c should colour the text red");
			check(gold > 30, "a JSON text component should be drawn in its colour");
			check(mini > 30, "MiniMessage colours should be drawn");

			int fullBright = frame.getRGB(Math.round(0.3f * frame.getWidth()), Math.round(0.62f * frame.getHeight())) & 0xFFFFFF;
			int dark = frame.getRGB(Math.round(0.4f * frame.getWidth()), Math.round(0.62f * frame.getHeight())) & 0xFFFFFF;
			log("White squares: full bright #" + Integer.toHexString(fullBright) + ", block/sky light 0 #" + Integer.toHexString(dark));
			check(fullBright == 0xFFFFFF, "a full-bright white square should stay pure white");
			check(brightness(dark) < 0.6 * brightness(fullBright), "a square with light level 0 should be drawn darker");
		} finally {
			context.runOnClient(minecraft -> {
				ProjectManager.getCurrent().elements.removeAll(added);
				ProjectManager.getCurrent().markDirty();
			});
		}
		checkFog(context);
	}

	/**
	 * A lit white square 60 blocks away, up in the sky: clear at the normal render distance, gone into the fog at render
	 * distance 2 (whose fog ends at about 32 blocks), like a text display. A see-through one next to it isn't fogged,
	 * like a see-through text display. The camera is lifted and tilted up for this, so nothing hides the sky.
	 */
	private static void checkFog(ClientGameTestContext context) {
		float[] fogged = {0.4f, 0.4f};
		float[] seeThrough = {0.6f, 0.4f};
		List<Element> added = new ArrayList<>();
		double[] savedCamera = context.computeOnClient(minecraft -> {
			var camera = minecraft.getCameraEntity();
			double[] saved = {camera.getX(), camera.getY(), camera.getZ(), camera.getYRot(), camera.getXRot()};
			camera.snapTo(camera.getX(), camera.getY() + 4, camera.getZ(), camera.getYRot(), -45);
			camera.setOldPosAndRot();
			return saved;
		});
		context.waitTicks(5);
		int previousDistance = context.computeOnClient(minecraft -> {
			for (float[] spot : List.of(fogged, seeThrough)) {
				Element square = square(Lighting.CUSTOM);
				square.name = "Fog check";
				float size = 0.06f * InFront.frameHeight(60);
				square.width = size;
				square.height = size;
				net.minecraft.world.phys.Vec3 position = InFront.at(spot[0], spot[1], 60);
				square.facing = Facing.CENTER;
				square.depthTest = spot == fogged;
				square.transform.set(Channel.X, position.x);
				square.transform.set(Channel.Y, position.y);
				square.transform.set(Channel.Z, position.z);
				added.add(square);
			}
			ProjectManager.getCurrent().elements.addAll(added);
			return minecraft.options.renderDistance().get();
		});
		try {
			BufferedImage clear = screenshotAt(context, 46, "fog-clear");
			context.runOnClient(minecraft -> minecraft.options.renderDistance().set(2));
			context.waitTicks(20);
			BufferedImage foggy = screenshotAt(context, 47, "fog-thick");

			int clearSquare = pixel(clear, fogged[0], fogged[1]);
			int foggySquare = pixel(foggy, fogged[0], fogged[1]);
			int foggySky = pixel(foggy, fogged[0], fogged[1] + 0.06f);
			int foggySeeThrough = pixel(foggy, seeThrough[0], seeThrough[1]);
			log("Fog: lit square at render distance " + previousDistance + " #" + Integer.toHexString(clearSquare) + ", at 2 #"
				+ Integer.toHexString(foggySquare) + " (fog around it #" + Integer.toHexString(foggySky) + "), see-through #"
				+ Integer.toHexString(foggySeeThrough));
			check(brightness(clearSquare) > 200, "the lit square should be visible and bright without fog");
			check(close(foggySquare, foggySky, 12), "in thick fog the lit square should take the fog's colour");
			check(brightness(foggySeeThrough) > 200, "see-through overlays shouldn't be fogged");
		} finally {
			context.runOnClient(minecraft -> {
				minecraft.options.renderDistance().set(previousDistance);
				ProjectManager.getCurrent().elements.removeAll(added);
				var camera = minecraft.getCameraEntity();
				camera.snapTo(savedCamera[0], savedCamera[1], savedCamera[2], (float) savedCamera[3], (float) savedCamera[4]);
				camera.setOldPosAndRot();
			});
			context.waitTicks(20);
		}
	}

	private static int pixel(BufferedImage image, float fx, float fy) {
		return image.getRGB(Math.round(fx * image.getWidth()), Math.round(fy * image.getHeight())) & 0xFFFFFF;
	}

	private static boolean close(int a, int b, int tolerance) {
		for (int shift = 0; shift <= 16; shift += 8) {
			if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > tolerance) {
				return false;
			}
		}
		return true;
	}

	/** Sizes, background, wrapping and formatting, against Minecraft's own font maths. */
	private static void checkLayout(Font font) {
		Element text = text("Hello");
		text.pivotX = 0.5f;
		text.pivotY = 1.0f;
		ElementGeometry.TextLayout layout = ElementGeometry.textLayout(text, text.content);
		check(layout != null && layout.lines().size() == 1, "one line");
		check(layout.width() == font.width("Hello") && layout.height() == 9, "a line is as wide as the font says and 9 pixels high");
		ElementGeometry.Bounds bounds = ElementGeometry.localBounds(text, text.content);
		// Like a text display: 1/40 block per pixel, background one pixel around the text, sitting on its position.
		check(near(bounds.maxX() - bounds.minX(), (font.width("Hello") + 1) * 0.025) && near(bounds.maxY() - bounds.minY(), 0.25),
			"text block should be (width + 1) x 10 pixels of 0.025 blocks: " + bounds);
		check(near(bounds.minY(), 0) && near(bounds.minX(), -bounds.maxX()), "the pivot should be the bottom centre: " + bounds);
		check(text.backgroundColor == 0x40000000 && !text.textShadow, "defaults should match vanilla text displays");

		check(width(text("&cRed")) == font.width("Red"), "formatting codes should take no space");
		check(width(text("§lBold")) == font.width("§lBold") && font.width("§lBold") > font.width("Bold"), "bold should be wider, as in Minecraft");
		check(width(text("{\"text\":\"Gold\",\"color\":\"gold\"}")) == font.width("Gold"), "JSON text should show its text only");
		check(width(text("{not json")) == font.width("{not json"), "text that only looks like JSON should show as it is");

		Element wrapped = text("the quick brown fox jumps over the lazy dog");
		wrapped.lineWidth = 60;
		ElementGeometry.TextLayout lines = ElementGeometry.textLayout(wrapped, wrapped.content);
		check(lines.lines().size() > 2, "long text should wrap at the line width");
		for (float lineWidth : lines.lineWidths()) {
			check(lineWidth <= 60, "wrapped lines should fit the line width: " + lineWidth);
		}
		check(lines.height() == lines.lines().size() * 10 - 1, "lines should be 10 pixels apart");
		check(ElementGeometry.textLayout(text("one\ntwo"), text("one\ntwo").content).lines().size() == 2, "newlines should break lines");

		// MiniMessage: tags take no space, styles apply, gradients colour each letter.
		check(width(text("<red>Red</red>")) == font.width("Red"), "MiniMessage tags should take no space");
		check(width(text("<bold>Bold</bold>")) == font.width("§lBold"), "MiniMessage <bold> should be bold");
		check(me.zhdg.billboards.render.RichText.detect("<gradient:red:blue>x</gradient>") == TextFormat.MINIMESSAGE, "auto: MiniMessage");
		check(me.zhdg.billboards.render.RichText.detect("I <3 & you") == TextFormat.CODES, "auto: plain text stays plain");
		check(me.zhdg.billboards.render.RichText.detect("&6<b>") == TextFormat.CODES, "auto: formatting codes win over tag-like text");
		Element literal = text("<red>Red</red>");
		literal.textFormat = TextFormat.CODES;
		check(width(literal) == font.width("<red>Red</red>"), "forcing formatting codes shows MiniMessage tags as text");
		Element gradient = text("<gradient:#ff0000:#0000ff>GRADIENT</gradient>");
		java.util.Set<Integer> colours = new java.util.HashSet<>();
		ElementGeometry.textLayout(gradient, gradient.content).lines().getFirst().accept((index, style, codePoint) -> {
			colours.add(style.getColor() == null ? -1 : style.getColor().getValue());
			return true;
		});
		check(colours.size() == 8 && !colours.contains(-1), "a MiniMessage gradient should give each letter its own colour: " + colours);
		Element rainbow = text("<rainbow>rainbow</rainbow> <shadow:#ff000080>shadow");
		check(width(rainbow) == font.width("rainbow shadow"), "rainbow and shadow tags");

		Element half = text("abcdef");
		half.content.set(Channel.REVEAL, 0.5);
		ElementGeometry.TextLayout revealed = ElementGeometry.textLayout(half, half.content);
		check(ElementGeometry.length(revealed.lines().getFirst()) == 3, "a half reveal should show half the characters");
		check(revealed.width() == font.width("abcdef"), "revealing should keep the full layout, so text doesn't shift");
		log("Text layout: vanilla size, background, wrapping, formatting codes, JSON text and reveal");
	}

	/** Billboard modes, with the same maths as vanilla display entities. */
	private static void checkBillboards() {
		OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
		Element element = new Element();
		ChannelValues transform = element.transform;
		Vector3f forward = camera.orientation().transform(new Vector3f(0, 0, -1));

		element.facing = Facing.CENTER;
		Quaternionf center = ElementGeometry.orientation(element, transform, camera, 0);
		Vector3f centerNormal = center.transform(new Vector3f(0, 0, 1));
		check(centerNormal.dot(forward) < -0.9999f, "center: the front should face the camera");

		element.facing = Facing.VERTICAL;
		Vector3f verticalNormal = ElementGeometry.orientation(element, transform, camera, 0).transform(new Vector3f(0, 0, 1));
		Vector3f flatForward = new Vector3f(forward.x, 0, forward.z).normalize();
		check(Math.abs(verticalNormal.y) < 1e-5 && verticalNormal.dot(flatForward) < -0.9999f, "vertical: upright, turned towards the camera");

		element.facing = Facing.HORIZONTAL;
		Quaternionf horizontal = ElementGeometry.orientation(element, transform, camera, 0);
		Vector3f horizontalRight = horizontal.transform(new Vector3f(1, 0, 0));
		Vector3f horizontalNormal = horizontal.transform(new Vector3f(0, 0, 1));
		check(horizontalRight.distance(1, 0, 0) < 1e-5 && near(horizontalNormal.y, -forward.y), "horizontal: keeps its yaw, tilts to the camera");
		log("Billboards: center, vertical and horizontal follow the camera like display entities");
	}

	private static void setUp(List<Element> added) {
		BillboardProject project = ProjectManager.getCurrent();

		Element red = text("&cRED &lred");
		InFront.place(red, 0.3f, 0.3f);
		Element gold = text("{\"text\":\"GOLD\",\"color\":\"gold\"}");
		InFront.place(gold, 0.3f, 0.42f);
		Element mini = text("<color:#2266ee>MINI</color> <gradient:gold:red>message</gradient>");
		InFront.place(mini, 0.3f, 0.52f);
		for (Element element : List.of(red, gold, mini)) {
			InFront.scale(element, InFront.textScale(0.06f));
			element.backgroundColor = 0;
			element.layer = 20;
		}

		Element fullBright = square(Lighting.FULL_BRIGHT);
		InFront.place(fullBright, 0.3f, 0.62f);
		Element dark = square(Lighting.CUSTOM);
		dark.blockLight = 0;
		dark.skyLight = 0;
		InFront.place(dark, 0.4f, 0.62f);
		check(OverlayRenderer.light(dark, ElementGeometry.worldPosition(dark, dark.transform)) == LightCoordsUtil.pack(0, 0), "custom light levels");
		check(OverlayRenderer.light(fullBright, ElementGeometry.worldPosition(fullBright, fullBright.transform)) < 0, "full bright skips the lightmap");

		added.addAll(List.of(red, gold, mini, fullBright, dark));
		project.elements.addAll(added);
		project.markDirty();
	}

	private static Element text(String content) {
		Element element = new Element();
		element.type = ElementType.TEXT;
		element.name = "Text check";
		element.content.text = content;
		return element;
	}

	private static Element square(Lighting lighting) {
		Element element = new Element();
		element.type = ElementType.SHAPE;
		element.name = "Light check";
		element.shapeKind = ShapeKind.RECTANGLE;
		element.fillColor = 0xFFFFFFFF;
		element.width = 0.06f * InFront.frameHeight();
		element.height = 0.06f * InFront.frameHeight();
		element.lighting = lighting;
		element.layer = 20;
		return element;
	}

	private static float width(Element text) {
		return ElementGeometry.textLayout(text, text.content).width();
	}

	private static double brightness(int rgb) {
		return (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3.0;
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

	private static boolean near(double a, double b) {
		return Math.abs(a - b) < 1e-4;
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
