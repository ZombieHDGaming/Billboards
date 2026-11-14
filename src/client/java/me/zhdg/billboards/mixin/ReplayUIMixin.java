package me.zhdg.billboards.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.editor.BillboardsWindow;
import me.zhdg.billboards.editor.ViewportTools;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ReplayUI.class, remap = false)
public class ReplayUIMixin {

	@Unique
	private static boolean billboards$loggedViewportError = false;

	/** Renders the Billboards window alongside Flashback's own windows. */
	@Inject(
		method = "drawOverlayInternal",
		at = @At(value = "INVOKE", target = "Lcom/moulberry/flashback/editor/ui/windows/WindowType;renderAll()V", shift = At.Shift.AFTER)
	)
	private static void billboards$renderWindow(CallbackInfo ci) {
		BillboardsWindow.render();
	}

	/**
	 * Viewport tools (selection outline, click-to-select, gizmos), run inside Flashback's game-frame window right
	 * after it has worked out where the frame is, so ImGuizmo sees the frame as the hovered window.
	 */
	@Inject(
		method = "drawOverlayInternal",
		at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImGui;isWindowFocused()Z", ordinal = 0)
	)
	private static void billboards$viewportTools(CallbackInfo ci) {
		try {
			ViewportTools.renderInFrame();
		} catch (Exception e) {
			if (!billboards$loggedViewportError) {
				billboards$loggedViewportError = true;
				Billboards.LOGGER.error("Error in Billboards viewport tools", e);
			}
		}
	}

	/** Don't grab the mouse for camera look when the click selected an overlay or is on a gizmo. */
	@Inject(method = "handleBasicInputs", at = @At("HEAD"), cancellable = true)
	private static void billboards$keepClicksForGizmos(CallbackInfo ci) {
		if (ViewportTools.ownsMouse()) {
			ci.cancel();
		}
	}
}
