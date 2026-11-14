package me.zhdg.billboards.mixin;

import com.moulberry.flashback.Flashback;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.render.OverlayRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screen-space overlays, drawn after the level, hand and screen effects but before the HUD/GUI. Doing it here
 * (rather than in the GUI pass) keeps them in exports that use Flashback's "No GUI" option.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

	@Unique
	private static boolean billboards$loggedError = false;

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void billboards$renderScreenOverlays(CallbackInfo ci) {
		if (!Flashback.isInReplay()) {
			return;
		}
		try {
			OverlayRenderer.renderScreen();
		} catch (Exception e) {
			if (!billboards$loggedError) {
				billboards$loggedError = true;
				Billboards.LOGGER.error("Error rendering screen overlays", e);
			}
		}
	}
}
