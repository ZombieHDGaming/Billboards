package me.zhdg.billboards.keyframe;

import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import com.moulberry.flashback.state.RealTimeMapping;
import me.zhdg.billboards.mixin.EditorStateAccessor;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.StampedLock;

/**
 * Evaluates Billboards' keyframe tracks at a tick.
 * <p>
 * Flashback's own {@code applyKeyframes} returns nothing after a track's last keyframe and relies on its targets
 * keeping their last value. Overlays are stateless, so we evaluate the tracks ourselves: the tick is clamped to the
 * track's first..last keyframe, then {@link KeyframeTrack#createKeyframeChange} does the interpolation exactly as it
 * does for camera and FOV tracks (all interpolation types, real-time mapping).
 */
public final class KeyframeEvaluator {

	/** Element id to the animated channels of that element. Channels without a track are absent. */
	public record Frame(Map<UUID, EnumMap<Channel, ChannelValues>> values) {
		public static final Frame EMPTY = new Frame(Collections.emptyMap());

		public @Nullable ChannelValues get(UUID elementId, Channel channel) {
			EnumMap<Channel, ChannelValues> channels = this.values.get(elementId);
			return channels == null ? null : channels.get(channel);
		}
	}

	private static Frame lastFrame = Frame.EMPTY;

	private KeyframeEvaluator() {
	}

	public static Frame evaluate(@Nullable PlaybackTime time) {
		if (time == null) {
			lastFrame = Frame.EMPTY;
			return lastFrame;
		}

		EditorState editorState = time.editorState();
		StampedLock lock = ((EditorStateAccessor) editorState).billboards$getSceneLock();
		long stamp = lock.tryReadLock();
		if (stamp == 0) {
			// The timeline is mid-edit on this thread; show the previous frame rather than deadlocking.
			return lastFrame;
		}
		try {
			EditorScene scene = editorState.getCurrentScene(stamp);
			RealTimeMapping realTimeMapping = ((EditorStateAccessor) editorState).billboards$getRealTimeMapping();
			lastFrame = evaluate(scene, time.tick(), realTimeMapping);
			return lastFrame;
		} finally {
			lock.unlockRead(stamp);
		}
	}

	private static Frame evaluate(EditorScene scene, float tick, @Nullable RealTimeMapping realTimeMapping) {
		Map<UUID, EnumMap<Channel, ChannelValues>> values = new HashMap<>();

		for (KeyframeTrack track : scene.keyframeTracks) {
			if (!track.enabled || !(track.keyframeType instanceof BillboardKeyframeTypes.Type type) || track.keyframesByTick.isEmpty()) {
				continue;
			}
			UUID elementId = elementOf(track);
			if (elementId == null) {
				continue;
			}
			EnumMap<Channel, ChannelValues> channels = values.computeIfAbsent(elementId, id -> new EnumMap<>(Channel.class));
			if (channels.containsKey(type.channel)) {
				continue; // First enabled track for an element/channel wins, as with Flashback's own tracks.
			}

			float clamped = Math.max(track.keyframesByTick.firstKey(), Math.min(track.keyframesByTick.lastKey(), tick));
			KeyframeChange change = track.createKeyframeChange(clamped, realTimeMapping);
			if (change instanceof ChannelChange channelChange && channelChange.values().channel == type.channel) {
				channels.put(type.channel, channelChange.values());
			}
		}

		return new Frame(values);
	}

	/** Whether the current scene of {@code editorState} has any Billboards track. */
	public static boolean hasBillboardTracks(EditorState editorState) {
		StampedLock lock = ((EditorStateAccessor) editorState).billboards$getSceneLock();
		long stamp = lock.tryReadLock();
		if (stamp == 0) {
			return true; // Being edited right now; assume it does rather than guessing otherwise.
		}
		try {
			for (KeyframeTrack track : editorState.getCurrentScene(stamp).keyframeTracks) {
				if (track.keyframeType instanceof BillboardKeyframeTypes.Type) {
					return true;
				}
			}
			return false;
		} finally {
			lock.unlockRead(stamp);
		}
	}

	/** The element a Billboards track belongs to, taken from its keyframes. */
	public static @Nullable UUID elementOf(KeyframeTrack track) {
		for (Keyframe keyframe : track.keyframesByTick.values()) {
			if (keyframe instanceof ChannelKeyframe channelKeyframe) {
				return channelKeyframe.elementId;
			}
		}
		return null;
	}
}
