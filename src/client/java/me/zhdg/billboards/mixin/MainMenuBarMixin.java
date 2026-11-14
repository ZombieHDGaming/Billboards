package me.zhdg.billboards.mixin;

import com.moulberry.flashback.editor.ui.windows.MainMenuBar;
import imgui.moulberry90.ImGui;
import me.zhdg.billboards.editor.BillboardsWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Billboards" entry to the end of Flashback's menu bar that toggles the Billboards window. */
@Mixin(value = MainMenuBar.class, remap = false)
public class MainMenuBarMixin {

	@Inject(method = "renderInner", at = @At("TAIL"))
	private static void billboards$addMenuEntry(CallbackInfo ci) {
		ImGui.separator();
		if (ImGui.menuItem("Billboards##BillboardsWindow", "", BillboardsWindow.isOpen())) {
			BillboardsWindow.toggle();
		}
	}
}
