package me.zhdg.billboards.test;

import com.moulberry.flashback.state.EditorState;
import me.zhdg.billboards.editor.BillboardsWindow;
import me.zhdg.billboards.keyframe.PlaybackTime;
import me.zhdg.billboards.keyframe.TrackEditor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.project.AssetStore;
import me.zhdg.billboards.project.Bundles;
import me.zhdg.billboards.project.ProjectManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Milestone 5: presets, packs and relinking missing files. */
final class BundleChecks {

	private BundleChecks() {
	}

	static void run(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			try {
				runOnClient();
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
	}

	private static void runOnClient() throws java.io.IOException {
		{
			BillboardProject project = ProjectManager.getCurrent();
			UUID replayId = ProjectManager.currentReplayId();
			EditorState editorState = PlaybackTime.liveEditorState();
			Element lowerThird = project.elements.stream().filter(e -> e.name.equals("Lower third")).findFirst().orElseThrow();

			// Preset: the lower third group, keyframes made relative (its group keyframes are at ticks 0 and 30).
			Path preset = Bundles.presetPath("Test lower third");
			Bundles.write(preset, replayId, project, editorState, List.of(lowerThird), true);
			check(Bundles.presetNames().contains("Test lower third"), "the preset should be listed");
			Bundles.Bundle bundle = Bundles.read(preset);
			check(bundle.elements().size() == 4, "the preset should hold the group and its 3 children, got " + bundle.elements().size());

			int before = project.elements.size();
			List<Element> roots = Bundles.insert(preset, bundle, replayId, project, editorState, 100, null);
			check(project.elements.size() == before + 4, "inserting the preset should add 4 elements");
			Element copy = roots.getFirst();
			check(copy.type == ElementType.GROUP && !copy.id.equals(lowerThird.id), "the inserted group should be a new element");
			check(project.children(copy.id).size() == 3, "the inserted group should contain its 3 children");
			List<TrackEditor.TrackData> tracks = TrackEditor.readTracks(editorState, Set.of(copy.id));
			List<Integer> ticks = new ArrayList<>();
			tracks.stream().filter(t -> t.channel() == Channel.TRANSFORM).forEach(t -> t.keyframes().forEach(k -> ticks.add(k.tick())));
			check(ticks.equals(List.of(100, 130)), "the preset's keyframes should start at the insertion tick: " + ticks);
			log("Preset: saved the lower third, inserted it at tick 100 with keyframes at " + ticks);

			// Pack: everything, with real ticks.
			Path pack = Files.createTempDirectory("billboards-pack").resolve("pack.zip");
			Bundles.write(pack, replayId, project, editorState, project.children(null), false);
			Bundles.Bundle packed = Bundles.read(pack);
			check(packed.elements().size() == project.elements.size(), "the pack should hold every element");
			check(!packed.relativeTicks(), "packs keep real ticks");
			long packedKeyframes = packed.tracks().stream().mapToLong(t -> t.keyframes().size()).sum();
			log("Pack: " + packed.elements().size() + " elements, " + packed.assets().size() + " files, " + packedKeyframes + " keyframes");

			// Re-import the pack: every element and keyframe comes back (as new elements), with the same ticks.
			int beforeImport = project.elements.size();
			List<Element> imported = Bundles.insert(pack, packed, replayId, project, editorState, 0, null);
			check(project.elements.size() == beforeImport * 2, "importing the pack should add a copy of every element");
			Set<UUID> importedIds = new java.util.HashSet<>();
			for (Element element : project.elements.subList(beforeImport, project.elements.size())) {
				importedIds.add(element.id);
			}
			long importedKeyframes = TrackEditor.readTracks(editorState, importedIds).stream().mapToLong(t -> t.keyframes().size()).sum();
			check(importedKeyframes == packedKeyframes, "importing should restore all " + packedKeyframes + " keyframes, got " + importedKeyframes);

			// Clean up the copies so later checks see the original scene.
			for (Element root : new ArrayList<>(imported)) {
				removeTree(project, editorState, root);
			}
			for (Element root : roots) {
				removeTree(project, editorState, root);
			}
			check(project.elements.size() == before, "cleanup should leave the original " + before + " elements, found " + project.elements.size()
				+ ": " + project.elements.stream().map(e -> e.name + (e.parentId == null ? "" : "(in group)")).toList());

			// Relink: an element whose file is missing gets pointed at a new file.
			Element broken = new Element();
			broken.type = ElementType.IMAGE;
			broken.hidden = true;
			broken.assetId = "0000000000000000000000000000000000000000000000000000000000000000.png";
			project.elements.add(broken);
			check(!AssetStore.exists(replayId, broken.assetId), "the broken element's file should be missing");
			Path replacement = Files.createTempDirectory("billboards-relink").resolve("replacement.png");
			javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", replacement.toFile());
			BillboardsWindow.relink(project, replayId, broken.assetId, replacement);
			check(AssetStore.exists(replayId, broken.assetId), "after relinking the element's file should exist");
			project.elements.remove(broken);
			log("Relink: a missing file was replaced and the element points at the new copy");
			project.markDirty();
		}
	}

	private static void removeTree(BillboardProject project, EditorState editorState, Element root) {
		for (Element element : project.elements.stream().filter(e -> project.isWithin(e, root.id)).toList()) {
			project.elements.remove(element);
			TrackEditor.removeTracks(editorState, element.id);
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
