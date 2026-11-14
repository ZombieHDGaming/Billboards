package me.zhdg.billboards.test;

import me.zhdg.billboards.Lang;
import me.zhdg.billboards.editor.ViewportTools;
import me.zhdg.billboards.keyframe.BillboardKeyframeTypes;
import me.zhdg.billboards.model.AttachAnchor;
import me.zhdg.billboards.model.BlendMode;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.model.Gradient;
import me.zhdg.billboards.model.ShapeKind;
import me.zhdg.billboards.model.Lighting;
import me.zhdg.billboards.model.TextAlign;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.locale.Language;

import java.util.ArrayList;
import java.util.List;

/** Milestone 5: UI text comes from the language file (run scripts/update_lang.py after changing it). */
final class LangChecks {

	private LangChecks() {
	}

	static void run(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			Language language = Language.getInstance();
			check(language.has("billboards.add_image"), "the language file should be loaded");
			check(Lang.tr("Add image...##id").equals("Add image...##id"), "ImGui ids should be kept and the text translated");
			check(Lang.tr("Exported %s overlays to %s", 3, "pack.zip").equals("Exported 3 overlays to pack.zip"), "format arguments should be filled in");
			check(Lang.tr("Text that isn't in any language file").equals("Text that isn't in any language file"), "untranslated text should fall back to English");

			List<String> missing = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (Enum<?>[] values : List.<Enum<?>[]>of(ElementType.values(), Lighting.values(), me.zhdg.billboards.model.TextFormat.values(), Facing.values(), TextAlign.values(),
				AttachAnchor.values(), ShapeKind.values(), Gradient.values(), BlendMode.values(), Channel.values(), ViewportTools.Tool.values())) {
				for (Enum<?> value : values) {
					try {
						names.add((String) value.getClass().getField("displayName").get(value));
					} catch (ReflectiveOperationException e) {
						throw new RuntimeException(e);
					}
				}
			}
			for (String name : names) {
				if (!language.has(Lang.key(name))) {
					missing.add(name);
				}
			}
			check(missing.isEmpty(), "these names have no language key (run scripts/update_lang.py): " + missing);
			check(BillboardKeyframeTypes.TRANSFORM.name().equals("Billboard Transform"), "keyframe type names should be translated");
			System.out.println("[Billboards test] Language file: " + names.size() + " names and UI text resolve through en_us.json");
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
