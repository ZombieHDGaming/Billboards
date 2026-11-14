package me.zhdg.billboards.project;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.model.BillboardProject;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Loads and saves the {@link BillboardProject} for the replay that is currently open.
 * <p>
 * Layout, next to Flashback's own {@code editor_states} folder:
 * <pre>
 * &lt;flashback data&gt;/billboards/projects/&lt;replay uuid&gt;/project.json
 *                                                 project.json.old
 *                                                 assets/&lt;sha256&gt;.&lt;ext&gt;
 * </pre>
 * Render thread only.
 */
public final class ProjectManager {

	private static final long SAVE_DELAY_MILLIS = 1000;

	private static @Nullable UUID currentReplay = null;
	private static @Nullable BillboardProject current = null;
	private static int savedModCount = 0;
	private static long lastChangeMillis = 0;
	private static int lastSeenModCount = 0;

	private ProjectManager() {
	}

	public static Path rootDirectory() {
		return Flashback.getDataDirectory().resolve("billboards");
	}

	public static Path projectDirectory(UUID replayId) {
		return rootDirectory().resolve("projects").resolve(replayId.toString());
	}

	public static Path assetsDirectory(UUID replayId) {
		return projectDirectory(replayId).resolve("assets");
	}

	/** The replay that is open in Flashback right now, or null outside a replay. */
	public static @Nullable UUID currentReplayId() {
		ReplayServer replayServer = Flashback.getReplayServer();
		if (replayServer == null) {
			return null;
		}
		return replayServer.getMetadata().replayIdentifier;
	}

	/** The project of the open replay, loading it on first use. Null outside a replay. */
	public static @Nullable BillboardProject getCurrent() {
		UUID replayId = currentReplayId();
		if (replayId == null) {
			return null;
		}
		if (!replayId.equals(currentReplay) || current == null) {
			flush();
			currentReplay = replayId;
			current = load(replayId);
			savedModCount = current.modCount;
			lastSeenModCount = current.modCount;
		}
		return current;
	}

	/** Called every client tick: saves shortly after the last change, and when the replay is closed. */
	public static void tick() {
		if (current == null) {
			return;
		}
		if (!java.util.Objects.equals(currentReplayId(), currentReplay)) {
			flush();
			current = null;
			currentReplay = null;
			return;
		}
		long now = System.currentTimeMillis();
		if (current.modCount != lastSeenModCount) {
			lastSeenModCount = current.modCount;
			lastChangeMillis = now;
		}
		if (current.modCount != savedModCount && now - lastChangeMillis >= SAVE_DELAY_MILLIS) {
			flush();
		}
	}

	/** Saves immediately if there are unsaved changes. */
	public static void flush() {
		if (current == null || currentReplay == null || current.modCount == savedModCount) {
			return;
		}
		try {
			save(currentReplay, current);
			savedModCount = current.modCount;
		} catch (IOException e) {
			Billboards.LOGGER.error("Unable to save Billboards project for replay {}", currentReplay, e);
		}
	}

	private static BillboardProject load(UUID replayId) {
		Path directory = projectDirectory(replayId);
		for (Path path : new Path[]{directory.resolve("project.json"), directory.resolve("project.json.old")}) {
			if (!Files.exists(path)) {
				continue;
			}
			try {
				BillboardProject project = ProjectGson.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), BillboardProject.class);
				if (project != null) {
					project.validate();
					return project;
				}
			} catch (Exception e) {
				Billboards.LOGGER.error("Unable to load Billboards project {}, trying backup", path, e);
			}
		}
		return new BillboardProject();
	}

	private static void save(UUID replayId, BillboardProject project) throws IOException {
		Path directory = projectDirectory(replayId);
		Files.createDirectories(directory);
		Path path = directory.resolve("project.json");
		Path backup = directory.resolve("project.json.old");
		Path temp = directory.resolve("project.json.tmp");

		Files.writeString(temp, ProjectGson.GSON.toJson(project), StandardCharsets.UTF_8);
		if (Files.exists(path)) {
			Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
	}
}
