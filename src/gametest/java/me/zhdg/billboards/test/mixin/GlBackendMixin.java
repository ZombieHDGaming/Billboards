package me.zhdg.billboards.test.mixin;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test-only: Xvfb's GLX has no sRGB-capable visuals, so Minecraft can't create its window on a headless machine.
 * With BILLBOARDS_NO_SRGB=1 set, stop requesting an sRGB-capable framebuffer (SDL_GL_FRAMEBUFFER_SRGB_CAPABLE = 22).
 */
@Mixin(GlBackend.class)
public class GlBackendMixin {

	@Inject(method = "createWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_CreateWindow(Ljava/lang/CharSequence;IIJ)J"))
	private void billboardsTest$noSrgb(String title, int width, int height, long flags, CallbackInfoReturnable<Long> cir) {
		if ("1".equals(System.getenv("BILLBOARDS_NO_SRGB"))) {
			SDLVideo.SDL_GL_SetAttribute(22, 0);
		}
	}
}
