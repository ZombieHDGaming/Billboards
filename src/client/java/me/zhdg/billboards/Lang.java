package me.zhdg.billboards;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

import java.util.Locale;

/**
 * Translates UI text through Minecraft's language files.
 * <p>
 * Text is written in English in the code; its key is {@code billboards.} plus the English text turned into a slug
 * (e.g. "Add image..." -> {@code billboards.add_image}). Every key is listed in {@code assets/billboards/lang/en_us.json};
 * a translation adds the same keys to its own language file. Missing translations fall back to the English text.
 * Anything after {@code ##} (an ImGui id) is kept as it is and not translated.
 */
public final class Lang {

	private Lang() {
	}

	/** The translation of {@code english}, formatted with {@code args} ({@code %s} placeholders) if there are any. */
	public static String tr(String english, Object... args) {
		int idStart = english.indexOf("##");
		String text = idStart >= 0 ? english.substring(0, idStart) : english;
		String id = idStart >= 0 ? english.substring(idStart) : "";
		String key = key(text);
		String translated = Language.getInstance().has(key) ? I18n.get(key, args) : (args.length == 0 ? text : String.format(Locale.ROOT, text, args));
		return translated + id;
	}

	/** The language-file key for a piece of English UI text. */
	public static String key(String english) {
		String slug = english.toLowerCase(Locale.ROOT)
			.replaceAll("%s", "x")
			.replaceAll("[^a-z0-9]+", "_")
			.replaceAll("^_+|_+$", "");
		if (slug.length() > 64) {
			slug = slug.substring(0, 64).replaceAll("_+$", "");
		}
		return "billboards." + (slug.isEmpty() ? "blank" : slug);
	}
}
