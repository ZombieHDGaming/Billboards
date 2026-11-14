package me.zhdg.billboards.keyframe;

import me.zhdg.billboards.Lang;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import me.zhdg.billboards.mixin.EditorStateAccessor;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;

/**
 * Creates and edits Billboards tracks in the current Flashback scene, through Flashback's undo history.
 * Render thread only.
 */
public final class TrackEditor {

	/** What the inspector needs to know about a channel at the cursor. */
	public enum KeyState {
		/** The channel has no track: edits change the base value. */
		NOT_ANIMATED,
		/** The channel is animated but the cursor is between keyframes. */
		ANIMATED,
		/** There is a keyframe exactly at the cursor. */
		ON_KEYFRAME
	}

	private TrackEditor() {
	}

	public static KeyState keyState(EditorState editorState, UUID elementId, Channel channel, int tick) {
		Boolean onKeyframe = read(editorState, scene -> {
			int index = findTrack(scene, elementId, channel);
			if (index < 0) {
				return null;
			}
			return scene.keyframeTracks.get(index).keyframesByTick.containsKey(tick);
		});
		if (onKeyframe == null) {
			return KeyState.NOT_ANIMATED;
		}
		return onKeyframe ? KeyState.ON_KEYFRAME : KeyState.ANIMATED;
	}

	/** Adds or replaces the keyframe at {@code tick}, creating the element's track for this channel if needed. */
	public static boolean setKeyframe(EditorState editorState, Element element, ChannelValues values, int tick) {
		Channel channel = values.channel;
		BillboardKeyframeTypes.Type type = BillboardKeyframeTypes.of(channel);
		return write(editorState, scene -> {
			int index = findTrack(scene, element.id, channel);
			if (index < 0) {
				index = scene.keyframeTracks.size();
				scene.push(new EditorSceneHistoryEntry(
					List.of(new EditorSceneHistoryAction.RemoveTrack(type, index)),
					List.of(new EditorSceneHistoryAction.AddTrack(type, index)),
					Lang.tr("Created %s track", type.name())));
				KeyframeTrack track = scene.keyframeTracks.get(index);
				track.customName = trackName(element, channel);
			}

			Keyframe existing = scene.keyframeTracks.get(index).keyframesByTick.get(tick);
			InterpolationType interpolation = existing != null ? existing.interpolationType() : InterpolationType.getDefault();
			scene.setKeyframe(index, tick, new ChannelKeyframe(element.id, values.copy(), interpolation));
			return true;
		});
	}

	public static boolean removeKeyframe(EditorState editorState, UUID elementId, Channel channel, int tick) {
		BillboardKeyframeTypes.Type type = BillboardKeyframeTypes.of(channel);
		return write(editorState, scene -> {
			int index = findTrack(scene, elementId, channel);
			if (index < 0) {
				return false;
			}
			Keyframe existing = scene.keyframeTracks.get(index).keyframesByTick.get(tick);
			if (existing == null) {
				return false;
			}
			scene.push(new EditorSceneHistoryEntry(
				List.of(new EditorSceneHistoryAction.SetKeyframe(type, index, tick, existing.copy())),
				List.of(new EditorSceneHistoryAction.RemoveKeyframe(type, index, tick)),
				Lang.tr("Removed %s keyframe", type.name())));
			return true;
		});
	}

	/** Removes every track of an element (all channels) from the current scene, undoably. */
	public static void removeTracks(EditorState editorState, UUID elementId) {
		write(editorState, scene -> {
			List<EditorSceneHistoryAction> undo = new ArrayList<>();
			List<EditorSceneHistoryAction> redo = new ArrayList<>();
			// Highest index first so earlier indices stay valid while removing.
			for (int index = scene.keyframeTracks.size() - 1; index >= 0; index--) {
				KeyframeTrack track = scene.keyframeTracks.get(index);
				if (track.keyframeType instanceof BillboardKeyframeTypes.Type && elementId.equals(KeyframeEvaluator.elementOf(track))) {
					redo.add(new EditorSceneHistoryAction.RemoveTrack(track.keyframeType, index));
					// Undo runs in order: re-add tracks lowest index first, then their keyframes.
					undo.addFirst(new EditorSceneHistoryAction.AddTrack(track.keyframeType, index));
					for (Map.Entry<Integer, Keyframe> entry : track.keyframesByTick.entrySet()) {
						undo.add(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, index, entry.getKey(), entry.getValue().copy()));
					}
				}
			}
			if (!redo.isEmpty()) {
				scene.push(new EditorSceneHistoryEntry(undo, redo, Lang.tr("Removed Billboards tracks")));
			}
			return true;
		});
	}

	/** Copies every track of {@code from} to {@code to} (a duplicated element), as one undoable step. */
	public static void duplicateTracks(EditorState editorState, UUID from, Element to) {
		write(editorState, scene -> {
			List<EditorSceneHistoryAction> undo = new ArrayList<>();
			List<EditorSceneHistoryAction> redo = new ArrayList<>();
			int nextIndex = scene.keyframeTracks.size();
			for (KeyframeTrack track : List.copyOf(scene.keyframeTracks)) {
				if (!(track.keyframeType instanceof BillboardKeyframeTypes.Type type) || !from.equals(KeyframeEvaluator.elementOf(track))) {
					continue;
				}
				int index = nextIndex++;
				redo.add(new EditorSceneHistoryAction.AddTrack(type, index));
				for (Map.Entry<Integer, Keyframe> entry : track.keyframesByTick.entrySet()) {
					ChannelKeyframe source = (ChannelKeyframe) entry.getValue();
					redo.add(new EditorSceneHistoryAction.SetKeyframe(type, index, entry.getKey(),
						new ChannelKeyframe(to.id, source.values.copy(), source.interpolationType())));
				}
				undo.addFirst(new EditorSceneHistoryAction.RemoveTrack(type, index));
			}
			if (redo.isEmpty()) {
				return false;
			}
			scene.push(new EditorSceneHistoryEntry(undo, redo, Lang.tr("Duplicated Billboards tracks")));
			for (KeyframeTrack track : scene.keyframeTracks) {
				if (track.keyframeType instanceof BillboardKeyframeTypes.Type type && to.id.equals(KeyframeEvaluator.elementOf(track))) {
					track.customName = trackName(to, type.channel);
				}
			}
			return true;
		});
	}

	/** One keyframe of a Billboards track, detached from Flashback's classes (for presets and packs). */
	public record KeyframeData(int tick, String interpolation, ChannelValues values) {
	}

	/** All keyframes of one element's channel. */
	public record TrackData(UUID elementId, Channel channel, List<KeyframeData> keyframes) {
	}

	/** Every Billboards track (current scene) belonging to one of {@code elementIds}. */
	public static List<TrackData> readTracks(EditorState editorState, java.util.Set<UUID> elementIds) {
		List<TrackData> result = read(editorState, scene -> {
			List<TrackData> tracks = new ArrayList<>();
			for (KeyframeTrack track : scene.keyframeTracks) {
				if (!(track.keyframeType instanceof BillboardKeyframeTypes.Type type)) {
					continue;
				}
				UUID elementId = KeyframeEvaluator.elementOf(track);
				if (elementId == null || !elementIds.contains(elementId)) {
					continue;
				}
				List<KeyframeData> keyframes = new ArrayList<>();
				for (Map.Entry<Integer, Keyframe> entry : track.keyframesByTick.entrySet()) {
					ChannelKeyframe keyframe = (ChannelKeyframe) entry.getValue();
					keyframes.add(new KeyframeData(entry.getKey(), keyframe.interpolationType().name(), keyframe.values.copy()));
				}
				tracks.add(new TrackData(elementId, type.channel, keyframes));
			}
			return tracks;
		});
		return result == null ? List.of() : result;
	}

	/** Adds a whole track for an element (e.g. from a preset), as one undoable step. */
	public static boolean addTrack(EditorState editorState, Element element, Channel channel, List<KeyframeData> keyframes) {
		if (keyframes.isEmpty()) {
			return false;
		}
		BillboardKeyframeTypes.Type type = BillboardKeyframeTypes.of(channel);
		return write(editorState, scene -> {
			int index = findTrack(scene, element.id, channel);
			List<EditorSceneHistoryAction> undo = new ArrayList<>();
			List<EditorSceneHistoryAction> redo = new ArrayList<>();
			boolean newTrack = index < 0;
			if (newTrack) {
				index = scene.keyframeTracks.size();
				redo.add(new EditorSceneHistoryAction.AddTrack(type, index));
				undo.add(new EditorSceneHistoryAction.RemoveTrack(type, index));
			}
			for (KeyframeData data : keyframes) {
				if (!newTrack) {
					// Undo puts back what was at this tick (or nothing).
					Keyframe old = scene.keyframeTracks.get(index).keyframesByTick.get(data.tick());
					undo.add(old != null
						? new EditorSceneHistoryAction.SetKeyframe(type, index, data.tick(), old.copy())
						: new EditorSceneHistoryAction.RemoveKeyframe(type, index, data.tick()));
				}
				InterpolationType interpolation;
				try {
					interpolation = InterpolationType.valueOf(data.interpolation());
				} catch (IllegalArgumentException e) {
					interpolation = InterpolationType.getDefault();
				}
				redo.add(new EditorSceneHistoryAction.SetKeyframe(type, index, data.tick(), new ChannelKeyframe(element.id, data.values().copy(), interpolation)));
			}
			scene.push(new EditorSceneHistoryEntry(undo, redo, Lang.tr("Added %s keyframes", type.name())));
			scene.keyframeTracks.get(index).customName = trackName(element, channel);
			return true;
		});
	}

	/** Keeps track names in sync after an element is renamed. */
	public static void renameTracks(EditorState editorState, Element element) {
		write(editorState, scene -> {
			for (KeyframeTrack track : scene.keyframeTracks) {
				if (track.keyframeType instanceof BillboardKeyframeTypes.Type type && element.id.equals(KeyframeEvaluator.elementOf(track))) {
					track.customName = trackName(element, type.channel);
				}
			}
			return true;
		});
	}

	public static String trackName(Element element, Channel channel) {
		return element.name + " · " + Lang.tr(channel.displayName);
	}

	private static int findTrack(EditorScene scene, UUID elementId, Channel channel) {
		BillboardKeyframeTypes.Type type = BillboardKeyframeTypes.of(channel);
		for (int i = 0; i < scene.keyframeTracks.size(); i++) {
			KeyframeTrack track = scene.keyframeTracks.get(i);
			if (track.keyframeType == type && elementId.equals(KeyframeEvaluator.elementOf(track))) {
				return i;
			}
		}
		return -1;
	}

	private static <T> @Nullable T read(EditorState editorState, Function<EditorScene, T> function) {
		StampedLock lock = ((EditorStateAccessor) editorState).billboards$getSceneLock();
		long stamp = lock.tryReadLock();
		if (stamp == 0) {
			return null;
		}
		try {
			return function.apply(editorState.getCurrentScene(stamp));
		} finally {
			lock.unlockRead(stamp);
		}
	}

	private static boolean write(EditorState editorState, Function<EditorScene, Boolean> function) {
		StampedLock lock = ((EditorStateAccessor) editorState).billboards$getSceneLock();
		long stamp = lock.tryWriteLock();
		if (stamp == 0) {
			return false;
		}
		boolean changed;
		try {
			changed = Boolean.TRUE.equals(function.apply(editorState.getCurrentScene(stamp)));
		} finally {
			lock.unlockWrite(stamp);
		}
		if (changed) {
			editorState.markDirty();
		}
		return changed;
	}
}
