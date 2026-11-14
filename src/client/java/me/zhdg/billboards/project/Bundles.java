package me.zhdg.billboards.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moulberry.flashback.state.EditorState;
import me.zhdg.billboards.keyframe.TrackEditor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.Space;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A self-contained set of elements with their asset files and keyframes, stored as a zip:
 * <pre>
 * bundle.json      elements, original asset names, tracks (keyframes)
 * assets/&lt;id&gt;     the asset files
 * </pre>
 * Presets are bundles of one element or group whose keyframe ticks start at 0, inserted at the timeline cursor.
 * Packs are bundles of a whole replay's overlays with their real ticks, for sharing a replay with its overlays.
 */
public final class Bundles {

	public static final String PRESET_EXTENSION = ".bbpreset";
	private static final String FORMAT = "billboards-bundle";
	private static final int VERSION = 1;

	/** What a bundle file holds, with its assets still in the zip. */
	public record Bundle(List<Element> elements, Map<String, String> assets, List<TrackEditor.TrackData> tracks, boolean relativeTicks) {
	}

	private Bundles() {
	}

	public static Path presetDirectory() {
		return ProjectManager.rootDirectory().resolve("presets");
	}

	/** Saved presets, by name. */
	public static List<String> presetNames() {
		List<String> names = new ArrayList<>();
		Path directory = presetDirectory();
		if (!Files.isDirectory(directory)) {
			return names;
		}
		try (var files = Files.list(directory)) {
			files.map(p -> p.getFileName().toString())
				.filter(name -> name.endsWith(PRESET_EXTENSION))
				.map(name -> name.substring(0, name.length() - PRESET_EXTENSION.length()))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(names::add);
		} catch (IOException ignored) {
		}
		return names;
	}

	public static Path presetPath(String name) {
		String safe = name.trim().replaceAll("[^A-Za-z0-9 _.()-]", "_");
		if (safe.isEmpty() || safe.startsWith(".")) {
			safe = "preset";
		}
		return presetDirectory().resolve(safe + PRESET_EXTENSION);
	}

	// ------------------------------------------------------------------------------------------------
	// Writing
	// ------------------------------------------------------------------------------------------------

	/**
	 * Writes {@code roots} and everything inside them.
	 *
	 * @param relativeTicks shift keyframe ticks (and media start ticks) so the earliest keyframe is at tick 0
	 */
	public static void write(Path file, UUID replayId, BillboardProject project, @Nullable EditorState editorState,
	                         List<Element> roots, boolean relativeTicks) throws IOException {
		List<Element> elements = new ArrayList<>();
		for (Element element : project.elements) {
			for (Element root : roots) {
				if (project.isWithin(element, root.id)) {
					elements.add(element);
					break;
				}
			}
		}
		Set<UUID> ids = new HashSet<>();
		elements.forEach(e -> ids.add(e.id));
		List<TrackEditor.TrackData> tracks = editorState == null ? List.of() : TrackEditor.readTracks(editorState, ids);

		int base = 0;
		if (relativeTicks) {
			base = Integer.MAX_VALUE;
			for (TrackEditor.TrackData track : tracks) {
				for (TrackEditor.KeyframeData keyframe : track.keyframes()) {
					base = Math.min(base, keyframe.tick());
				}
			}
			if (base == Integer.MAX_VALUE) {
				base = 0;
			}
		}

		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("version", VERSION);
		root.addProperty("relative_ticks", relativeTicks);
		JsonArray elementArray = new JsonArray();
		Map<String, String> assets = new LinkedHashMap<>();
		for (Element element : elements) {
			Element copy = element.copy();
			copy.id = element.id;
			copy.name = element.name;
			if (relativeTicks) {
				copy.mediaStartTick -= base;
			}
			// Parents outside the bundle (when saving part of a group) don't come along.
			if (copy.parentId != null && !ids.contains(copy.parentId)) {
				copy.parentId = null;
			}
			elementArray.add(ProjectGson.GSON.toJsonTree(copy));
			for (String assetId : new String[]{element.assetId, element.fontAssetId}) {
				if (assetId != null) {
					assets.put(assetId, project.assets.getOrDefault(assetId, assetId));
				}
			}
		}
		root.add("elements", elementArray);
		JsonObject assetObject = new JsonObject();
		assets.forEach(assetObject::addProperty);
		root.add("assets", assetObject);
		JsonArray trackArray = new JsonArray();
		for (TrackEditor.TrackData track : tracks) {
			JsonObject trackObject = new JsonObject();
			trackObject.addProperty("element", track.elementId().toString());
			trackObject.addProperty("channel", track.channel().id());
			JsonArray keyframes = new JsonArray();
			for (TrackEditor.KeyframeData keyframe : track.keyframes()) {
				JsonObject keyframeObject = new JsonObject();
				keyframeObject.addProperty("tick", keyframe.tick() - base);
				keyframeObject.addProperty("interpolation", keyframe.interpolation());
				keyframeObject.add("values", keyframe.values().toJson());
				keyframes.add(keyframeObject);
			}
			trackObject.add("keyframes", keyframes);
			trackArray.add(trackObject);
		}
		root.add("tracks", trackArray);

		Files.createDirectories(file.toAbsolutePath().getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
			zip.putNextEntry(new ZipEntry("bundle.json"));
			zip.write(ProjectGson.GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			for (String assetId : assets.keySet()) {
				Path source = AssetStore.resolve(replayId, assetId);
				if (Files.exists(source)) {
					zip.putNextEntry(new ZipEntry("assets/" + assetId));
					Files.copy(source, zip);
					zip.closeEntry();
				}
			}
		}
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	// ------------------------------------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------------------------------------

	public static Bundle read(Path file) throws IOException {
		JsonObject root = null;
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
				if (entry.getName().equals("bundle.json")) {
					root = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
				}
			}
		}
		if (root == null || !root.has("format") || !FORMAT.equals(root.get("format").getAsString())) {
			throw new IOException(file.getFileName() + " isn't a Billboards preset or pack");
		}
		List<Element> elements = new ArrayList<>();
		for (JsonElement json : root.getAsJsonArray("elements")) {
			Element element = ProjectGson.GSON.fromJson(json, Element.class);
			element.validate();
			elements.add(element);
		}
		Map<String, String> assets = new LinkedHashMap<>();
		if (root.has("assets")) {
			root.getAsJsonObject("assets").entrySet().forEach(e -> assets.put(e.getKey(), e.getValue().getAsString()));
		}
		List<TrackEditor.TrackData> tracks = new ArrayList<>();
		if (root.has("tracks")) {
			for (JsonElement json : root.getAsJsonArray("tracks")) {
				JsonObject track = json.getAsJsonObject();
				Channel channel = Channel.byId(track.get("channel").getAsString());
				List<TrackEditor.KeyframeData> keyframes = new ArrayList<>();
				for (JsonElement keyframeJson : track.getAsJsonArray("keyframes")) {
					JsonObject keyframe = keyframeJson.getAsJsonObject();
					keyframes.add(new TrackEditor.KeyframeData(keyframe.get("tick").getAsInt(),
						keyframe.get("interpolation").getAsString(), ChannelValues.fromJson(channel, keyframe.get("values"))));
				}
				tracks.add(new TrackEditor.TrackData(UUID.fromString(track.get("element").getAsString()), channel, keyframes));
			}
		}
		boolean relative = root.has("relative_ticks") && root.get("relative_ticks").getAsBoolean();
		return new Bundle(elements, assets, tracks, relative);
	}

	/**
	 * Adds a bundle's elements to the project with new ids, copying its assets in and adding its keyframes.
	 *
	 * @param tickOffset   added to every keyframe tick and media start tick (the cursor for presets, 0 for packs)
	 * @param worldMoveTo  for presets: move the bundle's top-level world elements so the first one lands here (keeping
	 *                     their layout and animation relative to it); null to keep positions
	 * @return the inserted top-level elements
	 */
	public static List<Element> insert(Path file, Bundle bundle, UUID replayId, BillboardProject project, @Nullable EditorState editorState,
	                                   int tickOffset, net.minecraft.world.phys.@Nullable Vec3 worldMoveTo) throws IOException {
		// Assets first, so the elements can point at their copies in this project.
		Map<String, String> assetIds = new HashMap<>();
		Path extracted = Files.createTempDirectory("billboards-bundle");
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
				String name = entry.getName();
				if (!name.startsWith("assets/") || entry.isDirectory()) {
					continue;
				}
				String assetId = name.substring("assets/".length());
				if (assetId.isEmpty() || assetId.contains("/") || assetId.contains("\\") || assetId.contains("..")) {
					continue;
				}
				// Extract under the original file name, which the project shows for the asset.
				String original = bundle.assets().getOrDefault(assetId, assetId).replaceAll("[^A-Za-z0-9 _.()-]", "_");
				Path temp = extracted.resolve(original.isBlank() || original.startsWith(".") ? assetId : original);
				try (OutputStream out = Files.newOutputStream(temp)) {
					zip.transferTo(out);
				}
				assetIds.put(assetId, AssetStore.importFile(replayId, project, temp));
				Files.deleteIfExists(temp);
			}
		} finally {
			try (var leftovers = Files.list(extracted)) {
				for (Path path : leftovers.toList()) {
					Files.deleteIfExists(path);
				}
			}
			Files.deleteIfExists(extracted);
		}

		Map<UUID, UUID> ids = new HashMap<>();
		bundle.elements().forEach(element -> ids.put(element.id, UUID.randomUUID()));

		double[] move = null;
		if (worldMoveTo != null) {
			for (Element element : bundle.elements()) {
				if (element.parentId == null && element.space == Space.WORLD && element.attachEntity == null) {
					move = new double[]{worldMoveTo.x - element.transform.get(Channel.X), worldMoveTo.y - element.transform.get(Channel.Y),
						worldMoveTo.z - element.transform.get(Channel.Z)};
					break;
				}
			}
		}

		List<Element> roots = new ArrayList<>();
		Map<UUID, Element> inserted = new HashMap<>();
		for (Element source : bundle.elements()) {
			Element element = source.copy();
			element.id = ids.get(source.id);
			element.name = source.name;
			element.parentId = source.parentId == null ? null : ids.get(source.parentId);
			if (element.assetId != null) {
				element.assetId = assetIds.getOrDefault(element.assetId, element.assetId);
			}
			if (element.fontAssetId != null) {
				element.fontAssetId = assetIds.getOrDefault(element.fontAssetId, element.fontAssetId);
			}
			element.mediaStartTick += tickOffset;
			boolean moved = move != null && element.parentId == null && element.space == Space.WORLD && element.attachEntity == null;
			if (moved) {
				shift(element.transform, move);
			}
			project.elements.add(element);
			inserted.put(source.id, element);
			if (element.parentId == null) {
				roots.add(element);
			}
		}

		if (editorState != null) {
			for (TrackEditor.TrackData track : bundle.tracks()) {
				Element element = inserted.get(track.elementId());
				if (element == null) {
					continue;
				}
				boolean moved = move != null && track.channel() == Channel.TRANSFORM && element.parentId == null
					&& element.space == Space.WORLD && element.attachEntity == null;
				List<TrackEditor.KeyframeData> keyframes = new ArrayList<>();
				for (TrackEditor.KeyframeData keyframe : track.keyframes()) {
					ChannelValues values = keyframe.values().copy();
					if (moved) {
						shift(values, move);
					}
					keyframes.add(new TrackEditor.KeyframeData(keyframe.tick() + tickOffset, keyframe.interpolation(), values));
				}
				TrackEditor.addTrack(editorState, element, track.channel(), keyframes);
			}
		}
		project.markDirty();
		return roots;
	}

	private static void shift(ChannelValues transform, double[] move) {
		transform.set(Channel.X, transform.get(Channel.X) + move[0]);
		transform.set(Channel.Y, transform.get(Channel.Y) + move[1]);
		transform.set(Channel.Z, transform.get(Channel.Z) + move[2]);
	}

}
