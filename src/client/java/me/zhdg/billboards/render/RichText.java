package me.zhdg.billboards.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.model.TextFormat;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a text overlay's content into a Minecraft chat component: formatting codes, MiniMessage tags or a JSON text
 * component. MiniMessage goes through Adventure's JSON serializer and then Minecraft's own text codec, so every style
 * MiniMessage can express that Minecraft can draw (colours, gradients, rainbows, bold, italic, fonts, shadow colours,
 * translations, keybinds...) comes out exactly as Minecraft would show it.
 */
public final class RichText {

	private static final Pattern LEGACY_CODE = Pattern.compile("&(?=[0-9a-fk-orA-FK-OR])");
	private static final Pattern HAS_LEGACY_CODE = Pattern.compile("[&§][0-9a-fk-orA-FK-OR]");
	/** A MiniMessage tag: {@code <red>}, {@code </bold>}, {@code <#ff8800>}, {@code <gradient:red:blue>}, {@code <!italic>}... */
	private static final Pattern MINIMESSAGE_TAG = Pattern.compile("</?[!#]?[a-zA-Z0-9_#][a-zA-Z0-9_\\-.#]*(?::[^<>]*)?/?>");

	private static final int CACHE_SIZE = 64;
	private static final Map<String, Component> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
			return this.size() > CACHE_SIZE;
		}
	};

	private static @Nullable MiniMessage miniMessage;

	private RichText() {
	}

	public static Component parse(String text, TextFormat format) {
		synchronized (CACHE) {
			return CACHE.computeIfAbsent(format.name() + ':' + text, key -> parseUncached(text, format));
		}
	}

	/** The format {@link TextFormat#AUTO} picks for a text. */
	public static TextFormat detect(String text) {
		String trimmed = text.strip();
		if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
			if (parseJson(trimmed) != null) {
				return TextFormat.JSON;
			}
		}
		if (MINIMESSAGE_TAG.matcher(text).find() && !HAS_LEGACY_CODE.matcher(text).find()) {
			return TextFormat.MINIMESSAGE;
		}
		return TextFormat.CODES;
	}

	private static Component parseUncached(String text, TextFormat format) {
		TextFormat actual = format == TextFormat.AUTO ? detect(text) : format;
		Component parsed = switch (actual) {
			case JSON -> parseJson(text.strip());
			case MINIMESSAGE -> parseMiniMessage(text);
			case CODES, AUTO -> null;
		};
		return parsed != null ? parsed : codes(text);
	}

	private static Component codes(String text) {
		return Component.literal(LEGACY_CODE.matcher(text).replaceAll("§"));
	}

	private static @Nullable Component parseJson(String json) {
		try {
			return fromJson(JsonParser.parseString(json));
		} catch (RuntimeException e) {
			return null; // not JSON after all
		}
	}

	private static @Nullable Component fromJson(JsonElement json) {
		ClientLevel level = Minecraft.getInstance().level;
		DynamicOps<JsonElement> ops = level == null ? JsonOps.INSTANCE : level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		return ComponentSerialization.CODEC.parse(ops, json).result().orElse(null);
	}

	private static @Nullable Component parseMiniMessage(String text) {
		try {
			if (miniMessage == null) {
				miniMessage = MiniMessage.miniMessage();
			}
			net.kyori.adventure.text.Component adventure = miniMessage.deserialize(text);
			return fromJson(GsonComponentSerializer.gson().serializeToTree(adventure));
		} catch (RuntimeException | LinkageError e) {
			Billboards.LOGGER.warn("Couldn't read MiniMessage text {}", text, e);
			return null;
		}
	}
}
