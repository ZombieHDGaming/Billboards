package me.zhdg.billboards.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import me.zhdg.billboards.editor.BillboardsWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Renders the Billboards window alongside Flashback's own windows. */
@Mixin(value = ReplayUI.class, remap = false)
public class ReplayUIMixin {

	@Inject(
		method = "drawOverlayInternal",
		at = @At(value = "INVOKE", target = "Lcom/moulberry/flashback/editor/ui/windows/WindowType;renderAll()V", shift = At.Shift.AFTER)
	)
	private static void billboards$renderWindow(CallbackInfo ci) {
		BillboardsWindow.render();
	}
}
