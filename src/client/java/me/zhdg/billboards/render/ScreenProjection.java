package me.zhdg.billboards.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Temporarily swaps in a pixel-space orthographic projection (origin top-left, y down) for the screen-space pass.
 * Using our own projection, instead of the level's, keeps titles still when the camera bobs, shakes or rolls.
 */
final class ScreenProjection {

	private static @Nullable ProjectionMatrixBuffer buffer = null;

	private ScreenProjection() {
	}

	static void begin(int width, int height) {
		if (buffer == null) {
			buffer = new ProjectionMatrixBuffer("billboards screen overlays");
		}
		RenderSystem.backupProjectionMatrix();
		// Same conventions as vanilla's GUI projection: reversed Z (near/far swapped) and the device's depth range.
		boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		Matrix4f projection = new Matrix4f().setOrtho(0, width, height, 0, 1000, -1000, zZeroToOne);
		RenderSystem.setProjectionMatrix(buffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
	}

	static void end() {
		RenderSystem.restoreProjectionMatrix();
	}
}
