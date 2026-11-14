package me.zhdg.billboards.test;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import me.zhdg.billboards.editor.ViewportTools;
import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.keyframe.TrackEditor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Gradient;
import me.zhdg.billboards.model.ShapeKind;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.model.TextAlign;
import me.zhdg.billboards.project.AssetStore;
import me.zhdg.billboards.project.ProjectManager;
import me.zhdg.billboards.render.CustomFont;
import me.zhdg.billboards.render.SceneFrame;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Milestone 3: a screen-space lower third built from a group containing a shape and TTF text, sliding in and fading
 * in as one; plus reparenting keeping elements in place.
 */
final class LowerThirdChecks {

	/** The lower third's solid shape colour, so it can be found in screenshots and exports. */
	static final int SHAPE_RGB = 0xFF8000;
	static final Path FONT = Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");

	private static UUID groupId;

	private LowerThirdChecks() {
	}

	static void run(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			try {
				build(minecraft);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		context.runOnClient(minecraft -> checkReparenting());

		long hidden = countPixels(screenshotAt(context, 0, "lower-third-tick-0"), SHAPE_RGB);
		long fading = countPixels(screenshotAt(context, 15, "lower-third-tick-15"), SHAPE_RGB);
		BufferedImage shown = screenshotAt(context, 40, "lower-third-tick-40");
		long visible = countPixels(shown, SHAPE_RGB);
		log("Lower third pure-orange pixels at ticks 0/15/40: " + hidden + " / " + fading + " / " + visible);
		check(hidden == 0, "the lower third should be off screen and transparent at tick 0");
		check(fading == 0, "the lower third should be half transparent at tick 15 (blended, not pure orange)");
		check(visible > 500, "the lower third should be fully visible at tick 40");

		// It slid in to x = 0.05 of the frame: its left edge should be there, at the bottom of the frame.
		int[] box = boundingBox(shown, SHAPE_RGB);
		float left = box[0] / (float) shown.getWidth();
		float top = box[1] / (float) shown.getHeight();
		log("Lower third left edge at " + left + ", top at " + top + " of the frame");
		check(Math.abs(left - 0.05f) < 0.02f, "the group should have moved the shape to x = 0.05");
		check(top > 0.7f, "the lower third should sit near the bottom of the frame");

		// Select the group so the outliner tree, group outline and gizmo show in the full-window screenshot.
		context.runOnClient(minecraft -> me.zhdg.billboards.editor.Selection.set(groupId));
		context.waitTicks(5);
		ViewportChecks.desktopScreenshot(context, "desktop-group-selected");

		if (Files.exists(FONT)) {
			long textPixels = countWhitish(shown, box);
			log("Bright text pixels inside the lower third: " + textPixels);
			check(textPixels > 50, "the TTF text should be drawn on the shape");
		}
	}

	private static void build(Minecraft minecraft) throws IOException {
		BillboardProject project = ProjectManager.getCurrent();
		UUID replayId = ProjectManager.currentReplayId();
		EditorState editorState = PlaybackTime.liveEditorState();

		Element group = new Element();
		groupId = group.id;
		group.name = "Lower third";
		group.type = ElementType.GROUP;
		group.space = Space.SCREEN;
		group.layer = 5;
		group.transform.set(Channel.X, 0.05);
		group.transform.set(Channel.Y, 0.85);
		project.elements.add(group);

		Element shape = new Element();
		shape.name = "Lower third background";
		shape.type = ElementType.SHAPE;
		shape.space = Space.SCREEN;
		shape.parentId = group.id;
		shape.shapeKind = ShapeKind.ROUNDED_RECTANGLE;
		shape.gradient = Gradient.NONE;
		shape.fillColor = 0xFF000000 | SHAPE_RGB;
		shape.width = 0.9f;
		shape.height = 0.14f;
		shape.cornerRadius = 0.03f;
		shape.pivotX = 0;
		shape.pivotY = 0.5f;
		project.elements.add(shape);

		Element accent = new Element();
		accent.name = "Accent line";
		accent.type = ElementType.SHAPE;
		accent.space = Space.SCREEN;
		accent.parentId = group.id;
		accent.shapeKind = ShapeKind.LINE;
		accent.fillColor = 0xFF1E64C8;
		accent.fillColor2 = 0xFFFFFFFF;
		accent.gradient = Gradient.HORIZONTAL;
		accent.width = 0.9f;
		accent.height = 0.02f;
		accent.strokeWidth = 0.008f;
		accent.pivotX = 0;
		accent.transform.set(Channel.Y, 0.08);
		project.elements.add(accent);

		Element text = new Element();
		text.name = "Lower third text";
		text.type = ElementType.TEXT;
		text.space = Space.SCREEN;
		text.parentId = group.id;
		text.content.text = "Lower third";
		text.fontSize = 0.08f;
		text.align = TextAlign.LEFT;
		text.pivotX = 0;
		text.pivotY = 0.5f;
		text.textShadow = false;
		text.transform.set(Channel.X, 0.03);
		if (Files.exists(FONT)) {
			text.fontAssetId = AssetStore.importFile(replayId, project, FONT);
		}
		project.elements.add(text);
		project.markDirty();

		if (text.fontAssetId != null) {
			CustomFont font = CustomFont.get(replayId, text.fontAssetId);
			check(font != null, "DejaVu Sans should load as a custom font");
			log("Custom font loaded: " + font.familyName());
		}

		// The group slides in from the left and fades in over ticks 0..30.
		ChannelValues start = group.transform.copy();
		start.set(Channel.X, -1.0);
		check(TrackEditor.setKeyframe(editorState, group, start, 0), "group position keyframe at 0");
		check(TrackEditor.setKeyframe(editorState, group, group.transform, 30), "group position keyframe at 30");
		ChannelValues transparent = group.appearance.copy();
		transparent.set(Channel.OPACITY, 0);
		check(TrackEditor.setKeyframe(editorState, group, transparent, 0), "group opacity keyframe at 0");
		check(TrackEditor.setKeyframe(editorState, group, group.appearance, 30), "group opacity keyframe at 30");
	}

	/** Moving an element into a rotated, scaled group must leave it exactly where it was. */
	private static void checkReparenting() {
		BillboardProject project = ProjectManager.getCurrent();
		Element group = new Element();
		group.type = ElementType.GROUP;
		group.space = Space.SCREEN;
		group.hidden = true;
		group.transform.set(Channel.X, 0.4);
		group.transform.set(Channel.Y, 0.3);
		group.transform.set(Channel.ROLL, 30);
		group.transform.set(Channel.SCALE_X, 2);
		group.transform.set(Channel.SCALE_Y, 2);
		Element child = new Element();
		child.type = ElementType.SHAPE;
		child.space = Space.SCREEN;
		child.hidden = true;
		child.transform.set(Channel.X, 0.7);
		child.transform.set(Channel.Y, 0.6);
		child.transform.set(Channel.ROLL, 10);
		project.elements.add(group);
		project.elements.add(child);
		try {
			SceneFrame before = SceneFrame.build(project, KeyframeEvaluator.Frame.EMPTY);
			Matrix4f original = before.screenModel(before.node(child.id), 1600, 900);
			ChannelValues local = ViewportTools.reparentedTransform(before, before.node(child.id), before.node(group.id));
			child.transform = local;
			child.parentId = group.id;
			SceneFrame after = SceneFrame.build(project, KeyframeEvaluator.Frame.EMPTY);
			Matrix4f moved = after.screenModel(after.node(child.id), 1600, 900);
			check(moved.equals(original, 0.5f), "reparenting should keep the element in place (to half a pixel):\n" + original + "\n" + moved);
			log("Reparenting into a rotated, scaled group keeps the element in place (local roll " + local.get(Channel.ROLL) + "°)");
		} finally {
			project.elements.remove(group);
			project.elements.remove(child);
		}
	}

	// ------------------------------------------------------------------------------------------------

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

	static long countPixels(BufferedImage image, int rgb) {
		long count = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) {
					count++;
				}
			}
		}
		return count;
	}

	/** {minX, minY, maxX, maxY} of the pixels with exactly this colour. */
	private static int[] boundingBox(BufferedImage image, int rgb) {
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) {
					box[0] = Math.min(box[0], x);
					box[1] = Math.min(box[1], y);
					box[2] = Math.max(box[2], x);
					box[3] = Math.max(box[3], y);
				}
			}
		}
		return box;
	}

	private static long countWhitish(BufferedImage image, int[] box) {
		long count = 0;
		for (int y = box[1]; y <= box[3]; y++) {
			for (int x = box[0]; x <= box[2]; x++) {
				int rgb = image.getRGB(x, y);
				if (((rgb >> 16) & 0xFF) > 230 && ((rgb >> 8) & 0xFF) > 230 && (rgb & 0xFF) > 230) {
					count++;
				}
			}
		}
		return count;
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
