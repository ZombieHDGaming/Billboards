package me.zhdg.billboards.test;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.state.EditorState;
import me.zhdg.billboards.keyframe.PlaybackTime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Checks real Flashback exports, which can't run inside Fabric's client gametest (see
 * {@link BillboardsClientGameTest#checkHiddenHud}). Run {@code ./gradlew runExportCheck} after
 * {@code runClientGameTest}: it opens the replay the game test saved (with its overlays and keyframes), exports a PNG
 * sequence like a video export and a "No GUI" screenshot, and checks the overlay's fade-in shows up in both.
 * Only active with {@code -Dbillboards.exportCheck=true}.
 */
public class ExportCheck implements ClientModInitializer {

	private static final int SCREEN_IMAGE_RGB = 0x00FF00;

	private enum State { OPEN, WAIT, EXPORT_SEQUENCE, EXPORT_SCREENSHOT, CHECK, DONE }

	private State state = State.OPEN;
	private int waitTicks = 0;
	private Path outputDir;

	@Override
	public void onInitializeClient() {
		if (!Boolean.getBoolean("billboards.exportCheck")) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft minecraft) {
		try {
			switch (this.state) {
				case OPEN -> {
					// Wait until the client has settled outside a world (the first screen may be onboarding, not the title).
					if (minecraft.level == null && minecraft.gui.overlay() == null && ++this.waitTicks > 100) {
						this.waitTicks = 0;
						log("Starting from screen " + (minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName()));
						Path replay = newestReplay();
						log("Opening " + replay);
						this.outputDir = Files.createTempDirectory("billboards-export-check");
						Flashback.openReplayWorld(replay);
						this.state = State.WAIT;
					}
				}
				case WAIT -> {
					if (Flashback.getReplayServer() != null && minecraft.level != null && minecraft.player != null && ++this.waitTicks > 100) {
						this.state = State.EXPORT_SEQUENCE;
					}
				}
				case EXPORT_SEQUENCE -> {
					// Like "Export video", but to a PNG sequence so the frames can be checked: ticks 0..40 at 20 fps.
					Path frames = Files.createDirectories(this.outputDir.resolve("frames"));
					startExport(minecraft, PlaybackTime.liveEditorState().copy(), 0, 40, 20, frames, "%04d");
					this.state = State.EXPORT_SCREENSHOT;
				}
				case EXPORT_SCREENSHOT -> {
					if (Flashback.EXPORT_JOB == null) {
						// Like "Export screenshot": the editor state is copied *without* keyframes.
						startExport(minecraft, PlaybackTime.liveEditorState().copyWithoutKeyframes(), 40, 40, 1,
							this.outputDir.resolve("screenshot.png"), null);
						this.state = State.CHECK;
					}
				}
				case CHECK -> {
					if (Flashback.EXPORT_JOB == null) {
						check();
						this.state = State.DONE;
						minecraft.stop();
					}
				}
				case DONE -> {
				}
			}
		} catch (Throwable t) {
			log("FAILED: " + t);
			t.printStackTrace();
			this.state = State.DONE;
			System.exit(1);
		}
	}

	private static void startExport(Minecraft minecraft, EditorState editorState, int start, int end, int framerate, Path output, String format) {
		var player = minecraft.player;
		VideoCodec codec = VideoContainer.PNG_SEQUENCE.getSupportedVideoCodecs(false)[0];
		ExportSettings settings = new ExportSettings(null, editorState,
			player.position(), player.getYRot(), player.getXRot(),
			640, 360, start, end,
			Flashback.getConfig().internalExport.projection, Flashback.getConfig().internalExport.orthographicZoom[0],
			framerate, false, false,
			VideoContainer.PNG_SEQUENCE, codec, codec.getEncoders()[0], 0, false, false, true, // noGui = true
			false, null,
			output, format);
		Flashback.EXPORT_JOB = new ExportJob(settings);
	}

	private void check() throws Exception {
		List<Path> frames;
		try (Stream<Path> files = Files.list(this.outputDir.resolve("frames"))) {
			frames = new ArrayList<>(files.filter(p -> p.toString().endsWith(".png")).sorted().toList());
		}
		if (frames.size() < 2) {
			throw new AssertionError("expected exported frames, found " + frames);
		}
		long first = countPixels(frames.getFirst());
		long last = countPixels(frames.getLast());
		long screenshot = countPixels(this.outputDir.resolve("screenshot.png"));
		log("Exported " + frames.size() + " frames; fully-green pixels in first/last frame: " + first + " / " + last
			+ "; in screenshot export: " + screenshot);
		if (first != 0) throw new AssertionError("overlay should be invisible in the first exported frame");
		if (last < 500) throw new AssertionError("overlay should be visible in the last exported frame");
		if (screenshot < 500) throw new AssertionError("overlay should be visible in the screenshot export");

		Path copy = Path.of(System.getProperty("billboards.exportCheck.copyTo", this.outputDir.toString()));
		Files.createDirectories(copy);
		Files.copy(frames.getFirst(), copy.resolve("export-first-frame.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.copy(frames.getLast(), copy.resolve("export-last-frame.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.copy(this.outputDir.resolve("screenshot.png"), copy.resolve("export-screenshot.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		log("All export checks passed");
	}

	private static long countPixels(Path path) throws Exception {
		BufferedImage image = ImageIO.read(path.toFile());
		long count = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getRGB(x, y) & 0xFFFFFF) == SCREEN_IMAGE_RGB) {
					count++;
				}
			}
		}
		return count;
	}

	private static Path newestReplay() throws Exception {
		try (Stream<Path> files = Files.list(Flashback.getReplayFolder())) {
			return files.filter(p -> p.toString().endsWith(".zip"))
				.max(Comparator.comparingLong(p -> p.toFile().lastModified()))
				.orElseThrow(() -> new AssertionError("No replay found; run runClientGameTest first"));
		}
	}

	private static void log(String message) {
		System.out.println("[Billboards export check] " + message);
	}
}
