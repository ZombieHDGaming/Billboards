package me.zhdg.billboards.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.moulberry.flashback.Flashback;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.render.OverlayRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** World-space overlays, drawn after everything else in the level, in the editor and in exports. */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	@Unique
	private static boolean billboards$loggedError = false;

	@Inject(method = "render", at = @At("RETURN"))
	private void billboards$renderWorldOverlays(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState,
	                                            GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired,
	                                            CallbackInfo ci) {
		if (!Flashback.isInReplay()) {
			return;
		}
		try {
			OverlayRenderer.renderWorld(cameraState);
		} catch (Exception e) {
			// Never take the replay down with us; log once and keep going.
			if (!billboards$loggedError) {
				billboards$loggedError = true;
				Billboards.LOGGER.error("Error rendering world overlays", e);
			}
		}
	}
}
