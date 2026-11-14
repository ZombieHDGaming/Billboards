package me.zhdg.billboards.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.project.AssetStore;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One {@link MediaPlayer} per element showing an animated image or a video (so the same file can play at different
 * times in different places). Render thread only.
 */
public final class MediaPlayers {

	private record Entry(String assetId, MediaPlayer player) {
	}

	private static final Map<UUID, Entry> PLAYERS = new HashMap<>();
	private static final Map<String, Boolean> ANIMATED = new HashMap<>();
	private static final Set<String> FAILED = new HashSet<>();
	private static @Nullable UUID loadedFor = null;

	private MediaPlayers() {
	}

	/** Whether this element is played rather than shown as a still image. */
	public static boolean isPlayed(UUID replayId, Element element) {
		if (element.type == ElementType.VIDEO) {
			return element.assetId != null;
		}
		if (element.type != ElementType.IMAGE || element.assetId == null) {
			return false;
		}
		syncReplay(replayId);
		return ANIMATED.computeIfAbsent(element.assetId, id -> {
			Path path = AssetStore.resolve(replayId, id);
			return Files.exists(path) && MediaPlayer.isAnimatedImage(path);
		});
	}

	/** The element's frame at a replay tick, or null if its media can't be played (callers draw a placeholder). */
	public static @Nullable GpuTextureView frame(UUID replayId, Element element, float tick) {
		MediaPlayer player = player(replayId, element);
		if (player == null) {
			return null;
		}
		try {
			return player.frameAt(MediaPlayer.secondsAt(tick, element.mediaStartTick, element.mediaSpeed), element.mediaLoop);
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to decode {}", element.assetId, e);
			FAILED.add(element.assetId);
			close(element.id);
			return null;
		}
	}

	public static @Nullable MediaPlayer player(UUID replayId, Element element) {
		syncReplay(replayId);
		String assetId = element.assetId;
		if (assetId == null || FAILED.contains(assetId)) {
			return null;
		}
		Entry entry = PLAYERS.get(element.id);
		if (entry != null && entry.assetId().equals(assetId)) {
			return entry.player();
		}
		close(element.id);
		try {
			Path path = AssetStore.resolve(replayId, assetId);
			if (!Files.exists(path)) {
				throw new java.io.FileNotFoundException(path.toString());
			}
			MediaPlayer player = MediaPlayer.open(path, assetId);
			PLAYERS.put(element.id, new Entry(assetId, player));
			return player;
		} catch (Exception e) {
			Billboards.LOGGER.error("Unable to open media asset {}", assetId, e);
			FAILED.add(assetId);
			return null;
		}
	}

	private static void close(UUID elementId) {
		Entry entry = PLAYERS.remove(elementId);
		if (entry != null) {
			entry.player().close();
		}
	}

	private static void syncReplay(UUID replayId) {
		if (!replayId.equals(loadedFor)) {
			clear();
			loadedFor = replayId;
		}
	}

	public static void clear() {
		PLAYERS.values().forEach(entry -> entry.player().close());
		PLAYERS.clear();
		ANIMATED.clear();
		FAILED.clear();
		loadedFor = null;
	}

	public static void retry(String assetId) {
		FAILED.remove(assetId);
		ANIMATED.remove(assetId);
	}
}
