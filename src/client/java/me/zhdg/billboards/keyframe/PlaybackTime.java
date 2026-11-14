package me.zhdg.billboards.keyframe;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import org.jetbrains.annotations.Nullable;

/**
 * Which editor state and replay tick overlays should show right now.
 * <p>
 * While exporting, Flashback renders from a copy of the editor state at an exact (fractional) tick, so we read
 * both from the export job; that keeps exported overlays frame-accurate with every other keyframe track.
 */
public record PlaybackTime(EditorState editorState, float tick) {

	public static @Nullable PlaybackTime current() {
		if (Flashback.isExporting()) {
			ExportJob job = Flashback.EXPORT_JOB;
			ExportSettings settings = job.getSettings();
			float tick = (float) (settings.startTick() + job.getCurrentTickDouble());
			EditorState exportState = settings.editorState();
			// "Export screenshot" copies the editor state without keyframes (so the camera stays put). Overlays should
			// still show their animated values, so fall back to the live editor state for the same replay.
			if (!KeyframeEvaluator.hasBillboardTracks(exportState)) {
				EditorState live = EditorStateManager.getCurrent();
				if (live != null) {
					exportState = live;
				}
			}
			return new PlaybackTime(exportState, tick);
		}

		ReplayServer replayServer = Flashback.getReplayServer();
		if (replayServer == null) {
			return null;
		}
		EditorState editorState = EditorStateManager.getCurrent();
		if (editorState == null) {
			return null;
		}
		return new PlaybackTime(editorState, (float) replayServer.getPartialReplayTick());
	}

	/** The whole tick the editor's cursor is on, used when adding keyframes. -1 outside a replay. */
	public static int cursorTick() {
		ReplayServer replayServer = Flashback.getReplayServer();
		return replayServer == null ? -1 : replayServer.getReplayTick();
	}
}
