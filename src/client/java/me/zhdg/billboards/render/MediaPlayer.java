package me.zhdg.billboards.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Plays an animated image (GIF, APNG) or a video, decoded with the FFmpeg that Flashback bundles (JavaCV).
 * <p>
 * {@link #frameAt} always shows the frame whose timestamp is the latest at or before the requested time, decoding
 * forwards (or seeking) synchronously. That makes exports frame-accurate and the same every time, whatever the export
 * frame rate. Render thread only.
 */
public final class MediaPlayer implements AutoCloseable {

	/** Jumps further ahead than this seek instead of decoding every frame in between. */
	private static final long SEEK_AHEAD_MICROS = 2_000_000;

	/** What {@link #probe} learns about a file without keeping it open. */
	public record Info(int width, int height, long durationMicros, double frameRate) {
		public double durationSeconds() {
			return this.durationMicros / 1e6;
		}
	}

	private final FFmpegFrameGrabber grabber;
	private final Info info;
	private final NativeImage image;
	private final DynamicTexture texture;
	private final ByteBuffer pending;
	private final long frameMicros;
	/** Animated images are short and their demuxers may not seek, so they always restart from the beginning instead. */
	private final boolean animatedImage;

	private boolean haveFrame = false;
	private long currentTimestamp = 0;
	private boolean hasPending = false;
	private long pendingTimestamp = 0;
	private boolean endOfStream = false;

	private MediaPlayer(FFmpegFrameGrabber grabber, Info info, String label, boolean animatedImage) {
		this.grabber = grabber;
		this.info = info;
		this.animatedImage = animatedImage;
		this.image = new NativeImage(info.width(), info.height(), true);
		this.texture = new DynamicTexture(() -> "billboards media " + label, this.image);
		this.pending = MemoryUtil.memAlloc(info.width() * info.height() * 4);
		this.frameMicros = (long) (1e6 / (info.frameRate() > 0 ? info.frameRate() : 25));
	}

	public static MediaPlayer open(Path path, String label) throws IOException {
		FFmpegFrameGrabber grabber = createGrabber(path);
		try {
			grabber.start();
			Info info = infoOf(grabber, isImageFile(path));
			if (info.width() <= 0 || info.height() <= 0) {
				throw new IOException("No video stream in " + path.getFileName());
			}
			return new MediaPlayer(grabber, info, label, isImageFile(path));
		} catch (IOException | RuntimeException e) {
			grabber.close();
			throw e instanceof IOException io ? io : new IOException(e);
		}
	}

	/** Size, length and frame rate of a media file. */
	public static Info probe(Path path) throws IOException {
		try (FFmpegFrameGrabber grabber = createGrabber(path)) {
			grabber.start();
			return infoOf(grabber, isImageFile(path));
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	/** Whether an image file has more than one frame (an animated GIF or PNG), so it should be played rather than shown. */
	public static boolean isAnimatedImage(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		try {
			if (name.endsWith(".png") || name.endsWith(".apng")) {
				return isApng(path);
			}
			if (name.endsWith(".gif")) {
				Info info = probe(path);
				return info.durationMicros() > 0 && info.durationMicros() > (long) (1e6 / Math.max(1, info.frameRate()));
			}
		} catch (IOException e) {
			return false;
		}
		return false;
	}

	private static FFmpegFrameGrabber createGrabber(Path path) throws IOException {
		FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path.toFile());
		grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		if ((name.endsWith(".png") || name.endsWith(".apng")) && isApng(path)) {
			// FFmpeg reads .png files as single images unless told they're animated.
			grabber.setFormat("apng");
		}
		return grabber;
	}

	private static boolean isImageFile(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".gif") || name.endsWith(".png") || name.endsWith(".apng");
	}

	/**
	 * Size, length and frame rate. Animated image demuxers don't always know their length (APNG reports 0 and a
	 * nonsense frame rate), so for those, and whenever the length is unknown, scan the frame timestamps once.
	 */
	private static Info infoOf(FFmpegFrameGrabber grabber, boolean scan) throws IOException {
		Info reported = new Info(grabber.getImageWidth(), grabber.getImageHeight(), Math.max(0, grabber.getLengthInTime()), grabber.getFrameRate());
		if (!scan && reported.durationMicros() > 0) {
			return reported;
		}
		try {
			long last = -1;
			long previous = -1;
			int frames = 0;
			for (Frame frame; frames < 100_000 && (frame = grabber.grabImage()) != null; frames++) {
				previous = last;
				last = frame.timestamp;
			}
			grabber.restart();
			if (frames <= 1 || last < 0) {
				return new Info(reported.width(), reported.height(), 0, reported.frameRate() > 0 && reported.frameRate() < 1000 ? reported.frameRate() : 25);
			}
			// The last frame lasts as long as the one before it.
			long lastFrame = previous >= 0 ? Math.max(1, last - previous) : 100_000;
			long duration = last + lastFrame;
			return new Info(reported.width(), reported.height(), duration, frames / (duration / 1e6));
		} catch (Exception e) {
			throw new IOException("Unable to read frame timing", e);
		}
	}

	/** An APNG has an "acTL" chunk before its image data. */
	private static boolean isApng(Path path) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			byte[] header = in.readNBytes(8);
			if (header.length < 8 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') {
				return false;
			}
			while (true) {
				byte[] chunk = in.readNBytes(8);
				if (chunk.length < 8) {
					return false;
				}
				int length = ((chunk[0] & 0xFF) << 24) | ((chunk[1] & 0xFF) << 16) | ((chunk[2] & 0xFF) << 8) | (chunk[3] & 0xFF);
				String type = new String(chunk, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
				if (type.equals("acTL")) {
					return true;
				}
				if (type.equals("IDAT") || type.equals("IEND") || length < 0) {
					return false;
				}
				in.skipNBytes(length + 4L); // data + CRC
			}
		}
	}

	public Info info() {
		return this.info;
	}

	/**
	 * The texture showing the media at {@code seconds} from its start. With {@code loop}, times past the end wrap
	 * around; otherwise the last frame holds.
	 */
	public GpuTextureView frameAt(double seconds, boolean loop) throws IOException {
		long target = (long) (Math.max(0, seconds) * 1e6);
		long duration = this.info.durationMicros();
		if (duration > 0) {
			target = loop ? target % duration : Math.min(target, duration);
		}

		boolean showingTarget = this.haveFrame && target >= this.currentTimestamp
			&& (this.hasPending ? target < this.pendingTimestamp : this.endOfStream);
		if (!showingTarget) {
			if (!this.haveFrame || target < this.currentTimestamp || target - this.currentTimestamp > SEEK_AHEAD_MICROS) {
				this.seek(target);
			}
			this.advanceTo(target);
		}
		return this.texture.getTextureView();
	}

	private void seek(long target) throws IOException {
		// Seek to the keyframe before the target without decoding past anything (advanceTo decodes forwards from there),
		// so the frame showing at the target is never skipped.
		long seekTo = Math.max(0, target - 2 * this.frameMicros);
		try {
			if (seekTo == 0 || this.animatedImage) {
				this.grabber.restart();
			} else {
				this.grabber.setTimestamp(seekTo, false);
			}
		} catch (Exception e) {
			throw new IOException("Seeking failed", e);
		}
		this.haveFrame = false;
		this.hasPending = false;
		this.endOfStream = false;
	}

	/** Decodes forwards until the next frame would be after {@code target}, uploading the last frame at or before it. */
	private void advanceTo(long target) throws IOException {
		boolean changed = false;
		while (true) {
			if (!this.hasPending && !this.endOfStream) {
				Frame frame;
				try {
					frame = this.grabber.grabImage();
				} catch (Exception e) {
					throw new IOException("Decoding failed", e);
				}
				if (frame == null || frame.image == null) {
					this.endOfStream = true;
				} else {
					this.copyToPending(frame);
					this.pendingTimestamp = frame.timestamp;
					this.hasPending = true;
				}
			}
			if (!this.hasPending) {
				break;
			}
			// Before the first frame's timestamp, show the first frame anyway.
			if (this.pendingTimestamp <= target || !this.haveFrame) {
				this.commitPending();
				changed = true;
			} else {
				break;
			}
		}
		if (changed) {
			this.texture.upload();
		}
	}

	private void copyToPending(Frame frame) {
		ByteBuffer source = (ByteBuffer) frame.image[0];
		int rowBytes = this.info.width() * 4;
		int stride = frame.imageStride > 0 ? frame.imageStride : rowBytes;
		int rows = Math.min(this.info.height(), frame.imageHeight);
		long src = MemoryUtil.memAddress(source);
		long dst = MemoryUtil.memAddress(this.pending);
		for (int y = 0; y < rows; y++) {
			MemoryUtil.memCopy(src + (long) y * stride, dst + (long) y * rowBytes, Math.min(rowBytes, stride));
		}
	}

	private void commitPending() {
		// FFmpeg's RGBA has the same byte order as NativeImage's RGBA.
		MemoryUtil.memCopy(MemoryUtil.memAddress(this.pending), this.image.getPointer(), (long) this.info.width() * this.info.height() * 4);
		this.currentTimestamp = this.pendingTimestamp;
		this.haveFrame = true;
		this.hasPending = false;
	}

	@Override
	public void close() {
		try {
			this.grabber.close();
		} catch (Exception ignored) {
		}
		this.texture.close();
		MemoryUtil.memFree(this.pending);
	}

	/** Seconds into a media element's playback at a replay tick. */
	public static double secondsAt(float tick, int startTick, float speed) {
		return Math.max(0, (tick - startTick) / 20.0 * speed);
	}
}
