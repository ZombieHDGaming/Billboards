package me.zhdg.billboards.test;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.project.AssetStore;
import me.zhdg.billboards.project.ProjectManager;
import me.zhdg.billboards.render.ElementGeometry;
import me.zhdg.billboards.render.MediaPlayer;
import me.zhdg.billboards.render.OverlayRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.phys.Vec3;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.joml.Vector3f;
import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Milestone 4: an animated GIF and a video play in sync with the replay, with loop / hold-last-frame and seeking. */
final class MediaChecks {

	static final int GIF_FRAME_A = 0x00FF80;
	static final int GIF_FRAME_B = 0x8000FF;
	static final int VIDEO_FIRST = 0x0000FF;
	static final int VIDEO_SECOND = 0xFFFF00;
	static final int APNG_FRAME_A = 0xFF0080;
	static final int APNG_FRAME_B = 0x80FF00;

	private MediaChecks() {
	}

	static void run(ClientGameTestContext context) {
		Path directory;
		try {
			directory = Files.createTempDirectory("billboards-media");
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		Path gif = directory.resolve("blink.gif");
		Path video = directory.resolve("clip.mov");
		Path apng = directory.resolve("blink.png");
		writeGif(gif);
		writeVideo(video);
		writeApng(apng);

		context.runOnClient(minecraft -> {
			try {
				setUp(gif, video, apng);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		checkFrames(context);
	}

	private static void setUp(Path gif, Path video, Path apng) throws IOException {
		{
			BillboardProject project = ProjectManager.getCurrent();
			UUID replayId = ProjectManager.currentReplayId();
			check(MediaPlayer.isAnimatedImage(gif), "the generated GIF should be detected as animated");
			MediaPlayer.Info info = MediaPlayer.probe(video);
			log("Test video: " + info.width() + "x" + info.height() + ", " + info.durationSeconds() + " s at " + info.frameRate() + " fps");
			check(Math.abs(info.durationSeconds() - 2) < 0.1, "the generated video should be 2 seconds long");

			Element animated = new Element();
			animated.name = "Animated GIF";
			animated.type = ElementType.IMAGE;
			animated.space = Space.SCREEN;
			animated.assetId = AssetStore.importFile(replayId, project, gif);
			animated.height = 0.15f;
			animated.width = 0.15f;
			animated.transform.set(Channel.X, 0.15);
			animated.transform.set(Channel.Y, 0.35);
			animated.mediaStartTick = 0;
			animated.mediaLoop = true;
			project.elements.add(animated);

			check(MediaPlayer.isAnimatedImage(apng), "the generated APNG should be detected as animated");
			Element animatedPng = new Element();
			animatedPng.name = "Animated PNG";
			animatedPng.type = ElementType.IMAGE;
			animatedPng.space = Space.SCREEN;
			animatedPng.assetId = AssetStore.importFile(replayId, project, apng);
			animatedPng.height = 0.15f;
			animatedPng.width = 0.15f;
			animatedPng.transform.set(Channel.X, 0.15);
			animatedPng.transform.set(Channel.Y, 0.6);
			project.elements.add(animatedPng);

			// A video on a "screen" in the world, to the left of the world image.
			OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
			Element worldImage = project.elements.stream().filter(e -> e.name.equals("World image")).findFirst().orElseThrow();
			Vec3 anchor = ElementGeometry.worldPosition(worldImage, worldImage.transform);
			Vector3f right = camera.orientation().transform(new Vector3f(1, 0, 0));
			Element clip = new Element();
			clip.name = "Video screen";
			clip.type = ElementType.VIDEO;
			clip.space = Space.WORLD;
			clip.facing = Facing.FACE_CAMERA;
			clip.depthTest = false;
			clip.assetId = AssetStore.importFile(replayId, project, video);
			clip.width = 1.6f;
			clip.height = 0.9f;
			clip.transform.set(Channel.X, anchor.x - right.x * 1.8);
			clip.transform.set(Channel.Y, anchor.y - right.y * 1.8);
			clip.transform.set(Channel.Z, anchor.z - right.z * 1.8);
			clip.mediaStartTick = 0;
			clip.mediaLoop = false;
			project.elements.add(clip);
			project.markDirty();
		}
	}

	private static void checkFrames(ClientGameTestContext context) {

		// Tick -> expected GIF frame (0.5 s = 10 ticks per frame, looping every 20 ticks) and video half (first second blue).
		int[][] expectations = {
			// tick, gif colour, video colour
			{5, GIF_FRAME_A, VIDEO_FIRST},
			{15, GIF_FRAME_B, VIDEO_FIRST},
			{25, GIF_FRAME_A, VIDEO_SECOND},
			{35, GIF_FRAME_B, VIDEO_SECOND},
			{60, GIF_FRAME_A, VIDEO_SECOND}, // video ended at 40 and holds its last frame
			{8, GIF_FRAME_A, VIDEO_FIRST},   // seeking backwards
		};
		for (int[] expectation : expectations) {
			int tick = expectation[0];
			BufferedImage image = screenshotAt(context, tick, "media-tick-" + tick);
			long gifRight = LowerThirdChecks.countPixels(image, expectation[1]);
			long gifWrong = LowerThirdChecks.countPixels(image, expectation[1] == GIF_FRAME_A ? GIF_FRAME_B : GIF_FRAME_A);
			long videoRight = LowerThirdChecks.countPixels(image, expectation[2]);
			long videoWrong = LowerThirdChecks.countPixels(image, expectation[2] == VIDEO_FIRST ? VIDEO_SECOND : VIDEO_FIRST);
			log("Tick " + tick + ": GIF right/wrong frame pixels " + gifRight + "/" + gifWrong + ", video " + videoRight + "/" + videoWrong);
			check(gifRight > 100 && gifWrong == 0, "GIF should show the expected frame at tick " + tick);
			check(videoRight > 100 && videoWrong == 0, "video should show the expected frame at tick " + tick);
			int apngExpected = expectation[1] == GIF_FRAME_A ? APNG_FRAME_A : APNG_FRAME_B;
			long apngRight = LowerThirdChecks.countPixels(image, apngExpected);
			long apngWrong = LowerThirdChecks.countPixels(image, apngExpected == APNG_FRAME_A ? APNG_FRAME_B : APNG_FRAME_A);
			check(apngRight > 100 && apngWrong == 0, "APNG should show the expected frame at tick " + tick + " (" + apngRight + "/" + apngWrong + ")");
		}
	}

	// ------------------------------------------------------------------------------------------------

	/** Two 0.5 s frames, looping forever. */
	private static void writeGif(Path path) {
		try (ImageOutputStream out = ImageIO.createImageOutputStream(path.toFile())) {
			ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
			writer.setOutput(out);
			writer.prepareWriteSequence(null);
			for (int colour : new int[]{GIF_FRAME_A, GIF_FRAME_B}) {
				BufferedImage frame = solid(64, 64, colour, BufferedImage.TYPE_INT_RGB);
				IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), null);
				String format = metadata.getNativeMetadataFormatName();
				IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
				IIOMetadataNode control = child(root, "GraphicControlExtension");
				control.setAttribute("disposalMethod", "none");
				control.setAttribute("userInputFlag", "FALSE");
				control.setAttribute("transparentColorFlag", "FALSE");
				control.setAttribute("delayTime", "50"); // hundredths of a second
				control.setAttribute("transparentColorIndex", "0");
				IIOMetadataNode applications = child(root, "ApplicationExtensions");
				IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
				loop.setAttribute("applicationID", "NETSCAPE");
				loop.setAttribute("authenticationCode", "2.0");
				loop.setUserObject(new byte[]{1, 0, 0});
				applications.appendChild(loop);
				metadata.setFromTree(format, root);
				writer.writeToSequence(new IIOImage(frame, null, metadata), null);
			}
			writer.endWriteSequence();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/**
	 * Two 0.5 s frames as an animated PNG, looping forever. ImageIO can't write APNG, so encode each frame as a PNG and
	 * reassemble the chunks: IHDR, acTL, then fcTL + IDAT for the first frame and fcTL + fdAT for the second.
	 */
	private static void writeApng(Path path) {
		try (java.io.DataOutputStream out = new java.io.DataOutputStream(Files.newOutputStream(path))) {
			out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
			int sequence = 0;
			int[] colours = {APNG_FRAME_A, APNG_FRAME_B};
			for (int frame = 0; frame < colours.length; frame++) {
				java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
				ImageIO.write(solid(64, 64, colours[frame], BufferedImage.TYPE_INT_RGB), "png", png);
				java.nio.ByteBuffer chunks = java.nio.ByteBuffer.wrap(png.toByteArray());
				chunks.position(8);
				while (chunks.remaining() >= 12) {
					int length = chunks.getInt();
					byte[] type = new byte[4];
					chunks.get(type);
					byte[] data = new byte[length];
					chunks.get(data);
					chunks.getInt(); // CRC
					String name = new String(type, java.nio.charset.StandardCharsets.US_ASCII);
					if (frame == 0 && name.equals("IHDR")) {
						writeChunk(out, "IHDR", data);
						writeChunk(out, "acTL", java.nio.ByteBuffer.allocate(8).putInt(colours.length).putInt(0).array());
					}
					if (name.equals("IDAT")) {
						if (frame == 0) {
							if (sequence == 0) {
								writeChunk(out, "fcTL", frameControl(sequence++));
							}
							writeChunk(out, "IDAT", data);
						} else {
							if (sequence == 1) {
								writeChunk(out, "fcTL", frameControl(sequence++));
							}
							writeChunk(out, "fdAT", java.nio.ByteBuffer.allocate(4 + data.length).putInt(sequence++).put(data).array());
						}
					}
				}
			}
			writeChunk(out, "IEND", new byte[0]);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private static byte[] frameControl(int sequence) {
		return java.nio.ByteBuffer.allocate(26)
			.putInt(sequence).putInt(64).putInt(64).putInt(0).putInt(0)
			.putShort((short) 1).putShort((short) 2) // delay 1/2 s
			.put((byte) 0).put((byte) 0)             // dispose none, blend source
			.array();
	}

	private static void writeChunk(java.io.DataOutputStream out, String type, byte[] data) throws IOException {
		byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(typeBytes);
		crc.update(data);
		out.writeInt(data.length);
		out.write(typeBytes);
		out.write(data);
		out.writeInt((int) crc.getValue());
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name) {
		for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node.getNodeName().equals(name)) {
				return (IIOMetadataNode) node;
			}
		}
		IIOMetadataNode node = new IIOMetadataNode(name);
		root.appendChild(node);
		return node;
	}

	/** 2 seconds at 20 fps, lossless (QuickTime RLE): blue for the first second, yellow for the second. */
	private static void writeVideo(Path path) {
		try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(path.toFile(), 160, 90)) {
			recorder.setFormat("mov");
			recorder.setVideoCodec(avcodec.AV_CODEC_ID_QTRLE);
			recorder.setPixelFormat(avutil.AV_PIX_FMT_RGB24);
			recorder.setFrameRate(20);
			recorder.start();
			try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
				for (int i = 0; i < 40; i++) {
					BufferedImage frame = solid(160, 90, i < 20 ? VIDEO_FIRST : VIDEO_SECOND, BufferedImage.TYPE_3BYTE_BGR);
					recorder.record(converter.convert(frame));
				}
			}
			recorder.stop();
		} catch (Exception e) {
			throw new RuntimeException("Unable to write the test video", e);
		}
	}

	private static BufferedImage solid(int width, int height, int rgb, int type) {
		BufferedImage image = new BufferedImage(width, height, type);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, 0xFF000000 | rgb);
			}
		}
		return image;
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
