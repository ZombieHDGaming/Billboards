package me.zhdg.billboards.test;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.playback.ReplayServer;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiConfigFlags;
import me.zhdg.billboards.editor.Selection;
import me.zhdg.billboards.editor.ViewportPicker;
import me.zhdg.billboards.editor.ViewportTools;
import me.zhdg.billboards.model.AttachAnchor;
import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Space;
import me.zhdg.billboards.project.ProjectManager;
import me.zhdg.billboards.render.ElementGeometry;
import me.zhdg.billboards.render.OverlayRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Milestone 2 checks: viewport picking, gizmo maths, entity attachment, and real mouse input when xdotool is available. */
final class ViewportChecks {

	private static UUID labelId;

	private ViewportChecks() {
	}

	static void run(ClientGameTestContext context, UUID screenId, UUID worldId) {
		seek(context, 40);
		checkPicking(context, screenId, worldId);
		context.runOnClient(minecraft -> checkGizmoMaths());
		checkAttachment(context);
		checkRealInput(context, screenId, worldId);
	}

	// ------------------------------------------------------------------------------------------------

	private static void checkPicking(ClientGameTestContext context, UUID screenId, UUID worldId) {
		context.runOnClient(minecraft -> {
			// The screen image is centred at (0.75, 0.75) of the frame.
			check(screenId.equals(ViewportPicker.pickScreen(0.75f, 0.75f)), "clicking the screen image's centre should pick it");
			check(ViewportPicker.pickScreen(0.05f, 0.95f) == null, "clicking empty screen space should pick nothing");

			BillboardProject project = ProjectManager.getCurrent();
			Element world = project.get(worldId);
			OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
			Vec3 target = ElementGeometry.worldPosition(world, world.transform);
			Vec3 towards = target.subtract(camera.position()).normalize();
			check(worldId.equals(ViewportPicker.pickWorld(towards)), "a ray through the world image's centre should pick it");
			check(ViewportPicker.pickWorld(towards.scale(-1)) == null, "a ray pointing away should pick nothing");
			log("Picking: screen and world overlays picked correctly");
		});
	}

	private static void checkGizmoMaths() {
		OverlayRenderer.SceneCamera camera = OverlayRenderer.lastCamera();
		Element element = new Element();
		element.space = Space.WORLD;
		element.facing = Facing.FIXED;
		element.transform.set(Channel.X, camera.position().x + 2);
		element.transform.set(Channel.Y, camera.position().y);
		element.transform.set(Channel.Z, camera.position().z + 3);
		element.transform.set(Channel.YAW, 350); // > 0..360 range on purpose: edits must keep it near here
		ChannelValues start = element.transform;
		Matrix4f model = ElementGeometry.worldModel(element, start, camera.position(), camera.orientation());

		ViewportTools.Tool previous = ViewportTools.tool;
		try {
			ViewportTools.tool = ViewportTools.Tool.MOVE;
			ChannelValues moved = ViewportTools.applyWorld(element, start, new Matrix4f().translate(1, 0, -0.5f).mul(model), camera);
			check(near(moved.get(Channel.X), start.get(Channel.X) + 1) && near(moved.get(Channel.Z), start.get(Channel.Z) - 0.5),
				"moving the gizmo should move the element: " + moved.toJson());
			check(near(moved.get(Channel.YAW), 350), "moving shouldn't change rotation");

			ViewportTools.tool = ViewportTools.Tool.ROTATE;
			ChannelValues rotated = ViewportTools.applyWorld(element, start, new Matrix4f(model).rotateY((float) Math.toRadians(30)), camera);
			check(near(rotated.get(Channel.YAW), 380), "rotating 30° from 350° should give 380°, not 20°: " + rotated.get(Channel.YAW));
			check(near(rotated.get(Channel.X), start.get(Channel.X)), "rotating shouldn't move the element");

			ViewportTools.tool = ViewportTools.Tool.SCALE;
			ChannelValues scaled = ViewportTools.applyWorld(element, start, new Matrix4f(model).scale(2, 0.5f, 1), camera);
			check(near(scaled.get(Channel.SCALE_X), 2) && near(scaled.get(Channel.SCALE_Y), 0.5), "scaling: " + scaled.toJson());

			ChannelValues screen = new ChannelValues(Channel.TRANSFORM);
			screen.set(Channel.X, 0.25);
			screen.set(Channel.Y, 0.4);
			screen.set(Channel.ROLL, 10);
			Matrix4f screenModel = ViewportTools.screenGizmoModel(screen, 800, 450);
			ViewportTools.tool = ViewportTools.Tool.MOVE;
			ChannelValues screenMoved = ViewportTools.applyScreen(screen, new Matrix4f().translate(80, -45, 0).mul(screenModel), 800, 450);
			check(near(screenMoved.get(Channel.X), 0.35) && near(screenMoved.get(Channel.Y), 0.5), "screen move: " + screenMoved.toJson());
			ChannelValues screenRotated = ViewportTools.applyScreen(screen, new Matrix4f(screenModel).rotateZ((float) Math.toRadians(20)), 800, 450);
			check(near(screenRotated.get(Channel.ROLL), 30), "screen rotate: " + screenRotated.get(Channel.ROLL));
		} finally {
			ViewportTools.tool = previous;
		}
		log("Gizmo maths: move, rotate (incl. past 360°) and scale map back to the right values");
	}

	private static void checkAttachment(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			Entity recorded = recordedPlayer(minecraft);
			check(recorded != null, "the recorded player should be in the replay");
			Element label = new Element();
			labelId = label.id;
			label.name = "Player label";
			label.type = ElementType.TEXT;
			label.space = Space.WORLD;
			label.facing = Facing.FACE_CAMERA;
			label.depthTest = false;
			label.content.text = "Player";
			label.attachEntity = recorded.getUUID();
			label.attachEntityName = recorded.getName().getString();
			label.attachAnchor = AttachAnchor.ABOVE_HEAD;
			ProjectManager.getCurrent().elements.add(label);
			ProjectManager.getCurrent().markDirty();
		});

		Vec3 before = labelOffsetCheck(context, 20);
		Vec3 after = labelOffsetCheck(context, 110);
		log("Attached label moved from " + before + " to " + after);
		// "tp ~8 ~ ~" also re-centres the player on the block, so the move isn't exactly 8.
		check(after.distanceTo(before) > 6, "the label should follow the player's ~8 block teleport");
	}

	/** Seeks, then checks the label sits right above the recorded player's head. Returns the label's world position. */
	private static Vec3 labelOffsetCheck(ClientGameTestContext context, int tick) {
		seek(context, tick);
		return context.computeOnClient(minecraft -> {
			Element label = ProjectManager.getCurrent().get(labelId);
			Entity recorded = ElementGeometry.attachedEntity(label);
			check(recorded != null, "attached entity should be loaded at tick " + tick);
			Vec3 position = ElementGeometry.worldPosition(label, label.transform);
			Vec3 expected = recorded.getPosition(ElementGeometry.partialTick()).add(0, recorded.getBbHeight() + 0.5, 0);
			check(position.distanceTo(expected) < 1e-3, "label should be above the head at tick " + tick + ": " + position + " vs " + expected);
			return position;
		});
	}

	private static Entity recordedPlayer(Minecraft minecraft) {
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (entity instanceof Player && entity != minecraft.player && entity != minecraft.getCameraEntity()) {
				return entity;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------
	// Real mouse input through the X server (only where xdotool is installed, e.g. headless CI with Xvfb)
	// ------------------------------------------------------------------------------------------------

	private static void checkRealInput(ClientGameTestContext context, UUID screenId, UUID worldId) {
		if (!hasCommand("xdotool")) {
			log("xdotool not found: skipping real mouse input checks");
			return;
		}
		seek(context, 40);
		context.runOnClient(minecraft -> {
			Selection.set(null);
			ViewportTools.tool = ViewportTools.Tool.MOVE;
		});
		context.waitTicks(5);
		desktopScreenshot(context, "desktop-before-click");

		// 1. Clicking the screen image in the viewport selects it.
		int[] centre = desktopPoint(context, 0.75f, 0.75f);
		xdotool("mousemove", centre[0], centre[1]);
		context.waitTicks(3);
		xdotool("click", "1");
		context.waitTicks(5);
		boolean selected = context.computeOnClient(minecraft -> Selection.is(screenId));
		log("Clicked the screen image at " + centre[0] + "," + centre[1] + "; selected: " + selected);
		check(selected, "clicking the screen image in the viewport should select it");
		desktopScreenshot(context, "desktop-selected");

		// 2. Dragging the gizmo's X arrow moves it. Find the arrow by hovering along +X from the centre.
		double startX = context.computeOnClient(minecraft -> ProjectManager.getCurrent().get(screenId).transform.get(Channel.X));
		int[] handle = null;
		for (int offset = 8; offset <= 200 && handle == null; offset += 4) {
			xdotool("mousemove", centre[0] + offset, centre[1]);
			context.waitTicks(1);
			if (context.computeOnClient(minecraft -> ViewportTools.isGizmoHovered())) {
				handle = new int[]{centre[0] + offset, centre[1]};
			}
		}
		check(handle != null, "the move gizmo should be hoverable to the right of the selected overlay");
		xdotool("mousedown", "1");
		context.waitTicks(2);
		for (int step = 1; step <= 10; step++) {
			xdotool("mousemove", handle[0] + step * 10, handle[1]);
			context.waitTicks(1);
		}
		desktopScreenshot(context, "desktop-dragging");
		xdotool("mouseup", "1");
		context.waitTicks(5);
		double endX = context.computeOnClient(minecraft -> ProjectManager.getCurrent().get(screenId).transform.get(Channel.X));
		log("Dragged the gizmo 100px right: x " + startX + " -> " + endX);
		check(endX > startX + 0.03, "dragging the gizmo should move the overlay right");
		desktopScreenshot(context, "desktop-after-drag");

		// 3. The same for a world overlay: click it, then drag its gizmo; it should move in the world.
		float[] worldFraction = context.computeOnClient(minecraft -> {
			Element world = ProjectManager.getCurrent().get(worldId);
			return ViewportTools.frameFraction(ElementGeometry.worldPosition(world, world.transform));
		});
		check(worldFraction != null, "the world image should be in front of the camera");
		int[] worldCentre = desktopPoint(context, worldFraction[0], worldFraction[1]);
		xdotool("mousemove", worldCentre[0], worldCentre[1]);
		context.waitTicks(3);
		xdotool("click", "1");
		context.waitTicks(5);
		check(context.computeOnClient(minecraft -> Selection.is(worldId)), "clicking the world image in the viewport should select it");
		desktopScreenshot(context, "desktop-world-selected");

		Vec3 worldStart = context.computeOnClient(minecraft -> worldPositionOf(worldId));
		int[] worldHandle = findGizmoHandle(context, worldCentre);
		check(worldHandle != null, "the world gizmo should be hoverable to the right of the world overlay");
		dragRight(context, worldHandle, 60);
		Vec3 worldEnd = context.computeOnClient(minecraft -> worldPositionOf(worldId));
		log("Dragged the world gizmo 60px right: " + worldStart + " -> " + worldEnd);
		check(worldEnd.distanceTo(worldStart) > 0.2, "dragging the world gizmo should move the overlay");
		desktopScreenshot(context, "desktop-world-after-drag");
	}

	private static Vec3 worldPositionOf(UUID id) {
		Element element = ProjectManager.getCurrent().get(id);
		return ElementGeometry.worldPosition(element, element.transform);
	}

	/** Hovers along +X from a point until the gizmo reports the mouse is over it. */
	private static int[] findGizmoHandle(ClientGameTestContext context, int[] from) {
		for (int offset = 8; offset <= 200; offset += 4) {
			xdotool("mousemove", from[0] + offset, from[1]);
			context.waitTicks(1);
			if (context.computeOnClient(minecraft -> ViewportTools.isGizmoHovered())) {
				return new int[]{from[0] + offset, from[1]};
			}
		}
		return null;
	}

	private static void dragRight(ClientGameTestContext context, int[] from, int pixels) {
		xdotool("mousedown", "1");
		context.waitTicks(2);
		for (int step = 1; step <= 10; step++) {
			xdotool("mousemove", from[0] + step * pixels / 10, from[1]);
			context.waitTicks(1);
		}
		xdotool("mouseup", "1");
		context.waitTicks(5);
	}

	/** A point of the game frame (as 0-1 fractions) in X screen coordinates. */
	private static int[] desktopPoint(ClientGameTestContext context, float fx, float fy) {
		float[] imgui = context.computeOnClient(minecraft -> {
			boolean viewports = ReplayUI.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable);
			float x = ReplayUI.frameX + fx * ReplayUI.frameWidth;
			float y = ReplayUI.frameY + fy * ReplayUI.frameHeight;
			// With multi-viewports ImGui coordinates are desktop coordinates; otherwise they're window-relative.
			return viewports
				? new float[]{x + ImGui.getMainViewport().getPosX(), y + ImGui.getMainViewport().getPosY(), 1}
				: new float[]{x, y, 0};
		});
		int[] window = imgui[2] == 1 ? new int[]{0, 0} : windowOrigin();
		return new int[]{Math.round(window[0] + imgui[0]), Math.round(window[1] + imgui[1])};
	}

	private static int[] windowOrigin() {
		String out = run("xdotool", "search", "--onlyvisible", "--name", "Minecraft", "getwindowgeometry", "--shell");
		int x = 0;
		int y = 0;
		for (String line : out.split("\n")) {
			if (line.startsWith("X=")) x = Integer.parseInt(line.substring(2).trim());
			if (line.startsWith("Y=")) y = Integer.parseInt(line.substring(2).trim());
		}
		return new int[]{x, y};
	}

	private static void xdotool(String command, Object... args) {
		List<String> parts = new ArrayList<>(List.of("xdotool", command));
		for (Object arg : args) {
			parts.add(String.valueOf(arg));
		}
		run(parts.toArray(String[]::new));
	}

	private static void desktopScreenshot(ClientGameTestContext context, String name) {
		if (!hasCommand("xwd") || !hasCommand("convert")) {
			return;
		}
		Path dir = context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots"));
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			return;
		}
		Path xwd = dir.resolve(name + ".xwd");
		run("xwd", "-root", "-silent", "-out", xwd.toString());
		run("convert", xwd.toString(), dir.resolve(name + ".png").toString());
		xwd.toFile().delete();
	}

	private static boolean hasCommand(String name) {
		try {
			return new ProcessBuilder("which", name).start().waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	private static String run(String... command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String out = new String(process.getInputStream().readAllBytes());
			if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
				throw new AssertionError(String.join(" ", command) + " failed: " + out);
			}
			return out;
		} catch (IOException | InterruptedException e) {
			throw new AssertionError(String.join(" ", command) + " failed", e);
		}
	}

	// ------------------------------------------------------------------------------------------------

	private static void seek(ClientGameTestContext context, int tick) {
		context.runOnClient(minecraft -> {
			ReplayServer replayServer = Flashback.getReplayServer();
			replayServer.replayPaused = true;
			replayServer.goToReplayTick(tick);
		});
		context.waitFor(minecraft -> Flashback.getReplayServer().getReplayTick() == tick, 20 * 30);
		context.waitTicks(10);
	}

	private static boolean near(double a, double b) {
		return Math.abs(a - b) < 1e-3;
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
