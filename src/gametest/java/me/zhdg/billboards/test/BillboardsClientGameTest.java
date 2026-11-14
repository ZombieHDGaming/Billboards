package me.zhdg.billboards.test;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import me.zhdg.billboards.editor.BillboardsWindow;
import me.zhdg.billboards.keyframe.ChannelKeyframe;
import me.zhdg.billboards.keyframe.KeyframeEvaluator;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.keyframe.TrackEditor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.project.AssetStore;
import me.zhdg.billboards.project.ProjectManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * End-to-end test: record a replay with Flashback, open it, add overlays with keyframes, and check what is drawn in
 * the editor and in an export.
 */
public class BillboardsClientGameTest implements FabricClientGameTest {

	private static UUID screenId;
	private static UUID worldId;

	/** Exact colours of the generated test images, so they can be found in screenshots. */
	private static final int SCREEN_IMAGE_RGB = 0x00FF00;
	private static final int WORLD_IMAGE_RGB = 0x00FFFF;

	@Override
	public void runTest(ClientGameTestContext context) {
		Path replay = recordReplay(context);
		log("Recorded replay " + replay);

		// Queue it on Minecraft's own task loop: Fabric's gametest defers world loads started from inside a test task, and
		// that deferral loses the thread-local Flashback uses to start its replay server instead of a normal one.
		context.runOnClient(minecraft -> {
			// Keep Flashback's "Tip of the day" popup from covering the viewport (the real-input checks click in it).
			Flashback.getConfig().internal.showTipOfTheDay = false;
			minecraft.schedule(() -> Flashback.openReplayWorld(replay));
		});
		waitForReplay(context);
		context.waitTicks(100); // let the editor UI, chunks and snapshot processing settle

		Path images;
		try {
			images = Files.createTempDirectory("billboards-test");
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		Path screenImage = writeSolidImage(images.resolve("fade.png"), 64, 32, SCREEN_IMAGE_RGB);
		Path worldImage = writeSolidImage(images.resolve("world.png"), 64, 64, WORLD_IMAGE_RGB);

		context.runOnClient(minecraft -> {
			try {
				setUpOverlays(minecraft, screenImage, worldImage);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		context.waitTicks(5);

		// The fade image goes from 0 opacity at tick 0 to 1 at tick 40.
		long atStart = countPixels(screenshotAt(context, 0, "billboards-tick-0"), SCREEN_IMAGE_RGB);
		long atMiddle = countPixels(screenshotAt(context, 20, "billboards-tick-20"), SCREEN_IMAGE_RGB);
		long atEnd = countPixels(screenshotAt(context, 40, "billboards-tick-40"), SCREEN_IMAGE_RGB);
		log("Fully-green fade image pixels at ticks 0/20/40: " + atStart + " / " + atMiddle + " / " + atEnd);
		check(atStart == 0, "the fade image should be invisible at tick 0");
		check(atMiddle == 0, "the fade image should be half-transparent (blended, not pure green) at tick 20");
		check(atEnd > 500, "the fade image should be fully visible at tick 40");

		BufferedImage tick60 = screenshotAt(context, 60, "billboards-tick-60");
		long titlePixels = countPixels(tick60, 0xFFFFFF);
		log("White title text pixels: " + titlePixels);
		check(titlePixels > 100, "title text should be drawn in white on top of its shadow");

		long worldPixels = countPixels(tick60, WORLD_IMAGE_RGB);
		log("World image pixels at tick 60: " + worldPixels);
		check(worldPixels > 500, "world image should be visible");

		context.runOnClient(BillboardsClientGameTest::checkKeyframesSurviveSerialization);
		context.runOnClient(minecraft -> {
			ProjectManager.flush();
			UUID replayId = ProjectManager.currentReplayId();
			check(Files.exists(ProjectManager.projectDirectory(replayId).resolve("project.json")), "project.json should be saved");
		});

		checkHiddenHud(context);
		ViewportChecks.run(context, screenId, worldId);
		TextDisplayChecks.run(context);
		LowerThirdChecks.run(context);
		MediaChecks.run(context);
		EffectsChecks.run(context);
		BundleChecks.run(context);
		LangChecks.run(context);
		log("All Billboards checks passed");

		// Leave the replay (on Minecraft's own task loop, for the same reason as opening it) so the test ends cleanly.
		context.runOnClient(minecraft -> minecraft.schedule(() -> {
			if (minecraft.level != null) {
				minecraft.level.disconnect(net.minecraft.network.chat.Component.empty());
			}
			minecraft.disconnectWithProgressScreen();
		}));
		context.waitFor(minecraft -> minecraft.getSingleplayerServer() == null && minecraft.level == null, 20 * 60);
		context.waitFor(minecraft -> !net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning, 20 * 60);

		// Hand the saved replay, editor state and Billboards project to the export check (./gradlew runExportCheck),
		// which runs in its own directory because Loom clears this one.
		context.waitFor(minecraft -> Files.isDirectory(Flashback.getDataDirectory().resolve("editor_states")), 20 * 60);
		context.runOnClient(minecraft -> copyTree(Flashback.getDataDirectory(),
			minecraft.gameDirectory.toPath().resolve("../exportCheck/flashback").normalize()));

		// Leaving a world briefly shows progress screens; end on the title screen once they're done.
		context.waitTicks(20);
		context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		context.waitForScreen(net.minecraft.client.gui.screens.TitleScreen.class);
	}

	private static void waitForReplay(ClientGameTestContext context) {
		for (int i = 0; i < 20 * 120; i++) {
			String status = context.computeOnClient(minecraft -> "replayServer=" + (Flashback.getReplayServer() != null)
				+ " level=" + (minecraft.level != null) + " player=" + (minecraft.player != null)
				+ " screen=" + (minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName())
				+ " editorState=" + (Flashback.getReplayServer() != null && PlaybackTime.liveEditorState() != null)
				+ " server=" + (minecraft.getSingleplayerServer() == null ? "null" : minecraft.getSingleplayerServer().getClass().getName())
				+ " thread=" + Thread.currentThread().getName());
			if (!status.contains("false")) {
				log("Replay open: " + status);
				return;
			}
			if (i % 200 == 0) {
				log("Waiting for replay: " + status);
				if (i > 0) {
					context.takeScreenshot("billboards-waiting-" + i);
				}
			}
			context.waitTick();
		}
		throw new AssertionError("Replay did not open");
	}

	private static Path recordReplay(ClientGameTestContext context) {
		Path replayFolder = context.computeOnClient(minecraft -> Flashback.getReplayFolder());
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.runOnClient(minecraft -> {
				Flashback.getConfig().recordingControls.quicksave = true;
				Flashback.startRecordingReplay();
			});
			context.waitTicks(60);
			// Move the player half way through, so the replay has a moving entity to attach a label to.
			singleplayer.getServer().runCommand("tp @a ~8 ~ ~");
			context.waitTicks(60);
			context.runOnClient(minecraft -> Flashback.finishRecordingReplay());

			for (int i = 0; i < 600; i++) {
				Optional<Path> zip = newestZip(replayFolder);
				if (zip.isPresent() && Files.exists(zip.get()) && !Files.exists(zip.get().resolveSibling(zip.get().getFileName() + ".tmp"))) {
					context.waitTicks(40); // make sure the writer has finished
					return zip.get();
				}
				context.waitTick();
			}
			throw new AssertionError("Flashback did not write a replay into " + replayFolder);
		}
	}

	private static void setUpOverlays(Minecraft minecraft, Path screenImage, Path worldImage) throws IOException {
		BillboardProject project = ProjectManager.getCurrent();
		UUID replayId = ProjectManager.currentReplayId();
		EditorState editorState = PlaybackTime.liveEditorState();
		check(project != null && replayId != null && editorState != null, "replay should be open");

		Flashback.getConfig().internal.openedWindows.add(BillboardsWindow.WINDOW_ID);

		// An image in the bottom-right corner of the frame, fading in over ticks 0..40.
		Element screen = new Element();
		screenId = screen.id;
		screen.name = "Fade image";
		screen.type = ElementType.IMAGE;
		screen.assetId = AssetStore.importFile(replayId, project, screenImage);
		screen.height = 0.2f * InFront.frameHeight();
		screen.width = 0.4f * InFront.frameHeight();
		InFront.place(screen, 0.75f, 0.75f);
		project.elements.add(screen);

		ChannelValues hidden = screen.appearance.copy();
		hidden.set(Channel.OPACITY, 0);
		ChannelValues shown = screen.appearance.copy();
		shown.set(Channel.OPACITY, 1);
		check(TrackEditor.setKeyframe(editorState, screen, hidden, 0), "set keyframe at tick 0");
		check(TrackEditor.setKeyframe(editorState, screen, shown, 40), "set keyframe at tick 40");

		// Title text at the top of the frame.
		Element title = new Element();
		title.name = "Title";
		title.type = ElementType.TEXT;
		title.content.text = "Billboards test\nsecond line";
		title.textShadow = true;
		title.backgroundColor = 0x80000000;
		InFront.place(title, 0.5f, 0.15f);
		InFront.scale(title, InFront.textScale(0.08f));
		project.elements.add(title);

		// World image 4 blocks in front of the camera, always on top so terrain can't hide it.
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		Vector3fc forward = camera.forwardVector();
		Element world = new Element();
		worldId = world.id;
		world.name = "World image";
		world.type = ElementType.IMAGE;
		world.facing = Facing.CENTER;
		world.depthTest = false;
		world.assetId = AssetStore.importFile(replayId, project, worldImage);
		world.width = 1.5f;
		world.height = 1.5f;
		world.transform.set(Channel.X, eye.x + forward.x() * 4);
		world.transform.set(Channel.Y, eye.y + forward.y() * 4);
		world.transform.set(Channel.Z, eye.z + forward.z() * 4);
		project.elements.add(world);

		// World text that spins, to exercise rotation keyframes.
		Element sign = new Element();
		sign.name = "World text";
		sign.type = ElementType.TEXT;
		sign.facing = Facing.VERTICAL;
		sign.content.text = "Hello world";
		sign.transform.set(Channel.X, eye.x + forward.x() * 6);
		sign.transform.set(Channel.Y, eye.y + forward.y() * 6 + 1.5);
		sign.transform.set(Channel.Z, eye.z + forward.z() * 6);
		project.elements.add(sign);
		ChannelValues spin = sign.transform.copy();
		spin.set(Channel.ROLL, 360);
		check(TrackEditor.setKeyframe(editorState, sign, sign.transform, 0), "set sign keyframe at 0");
		check(TrackEditor.setKeyframe(editorState, sign, spin, 80), "set sign keyframe at 80");

		project.markDirty();

		// The evaluator should interpolate halfway between the two opacity keyframes.
		ChannelValues halfway = KeyframeEvaluator.evaluate(new PlaybackTime(editorState, 20)).get(screen.id, Channel.APPEARANCE);
		check(halfway != null, "the fade image should have an appearance track");
		log("Opacity at tick 20: " + halfway.get(Channel.OPACITY));
		check(halfway.get(Channel.OPACITY) > 0.05 && halfway.get(Channel.OPACITY) < 0.95, "opacity should be between keyframes at tick 20");
		ChannelValues after = KeyframeEvaluator.evaluate(new PlaybackTime(editorState, 500)).get(screen.id, Channel.APPEARANCE);
		check(after != null && Math.abs(after.get(Channel.OPACITY) - 1) < 1e-6, "after the last keyframe the last value should hold");
	}

	private static void checkKeyframesSurviveSerialization(Minecraft minecraft) {
		EditorState editorState = PlaybackTime.liveEditorState();
		EditorState copy = editorState.copy(); // round-trips through Flashback's Gson, including our TypeAdapter mixin
		long stamp = copy.acquireRead();
		try {
			EditorScene scene = copy.getCurrentScene(stamp);
			int found = 0;
			for (KeyframeTrack track : scene.keyframeTracks) {
				for (Keyframe keyframe : track.keyframesByTick.values()) {
					if (keyframe instanceof ChannelKeyframe) {
						found++;
					}
				}
			}
			log("Billboards keyframes after JSON round trip: " + found);
			check(found == 4, "expected 4 Billboards keyframes after round trip, found " + found);
		} finally {
			copy.release(stamp);
		}
	}

	/**
	 * Overlays must not be part of the GUI pass, or Flashback exports with "No GUI" would drop them. Hide the HUD and
	 * check they are still drawn.
	 * <p>
	 * (A real Flashback export can't run inside Fabric's client gametest: the gametest steps the server in lock-step
	 * with client ticks, while an export blocks the client until the server reaches the export tick, so both wait
	 * forever. Exports are checked by hand instead.)
	 */
	private static void checkHiddenHud(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			if (!minecraft.gui.hud.isHidden()) {
				minecraft.gui.hud.toggle();
			}
		});
		long pixels = countPixels(screenshotAt(context, 41, "billboards-hud-hidden"), SCREEN_IMAGE_RGB);
		log("Fade image pixels with the HUD hidden: " + pixels);
		check(pixels > 500, "overlays should still render with the HUD hidden");
		context.runOnClient(minecraft -> minecraft.gui.hud.toggle());
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
		return read(context.takeScreenshot(name));
	}

	private static long countPixels(BufferedImage image, int rgb) {
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

	private static Path writeSolidImage(Path path, int width, int height, int rgb) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, 0xFF000000 | rgb);
			}
		}
		try {
			ImageIO.write(image, "png", path.toFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return path;
	}

	private static BufferedImage read(Path path) {
		try {
			return ImageIO.read(path.toFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static Optional<Path> newestZip(Path folder) {
		if (!Files.isDirectory(folder)) {
			return Optional.empty();
		}
		try (Stream<Path> files = Files.list(folder)) {
			List<Path> zips = files.filter(p -> p.getFileName().toString().endsWith(".zip")).toList();
			return zips.stream().max(Comparator.comparingLong(p -> p.toFile().lastModified()));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	private static void copyTree(Path from, Path to) {
		try (Stream<Path> paths = Files.walk(from)) {
			for (Path path : paths.toList()) {
				Path target = to.resolve(from.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(target);
				} else {
					Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
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
