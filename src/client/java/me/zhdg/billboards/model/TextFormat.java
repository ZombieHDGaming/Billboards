package me.zhdg.billboards.model;

/** How a text overlay's content is read. */
public enum TextFormat {
	/** JSON if the text is a JSON text component, MiniMessage if it has MiniMessage tags, otherwise formatting codes. */
	AUTO("Auto"),
	/** Plain text with {@code &} or {@code §} formatting codes ({@code &6&lGold bold}). */
	CODES("Formatting codes"),
	/** MiniMessage tags ({@code <gradient:gold:red>Hi</gradient>}). */
	MINIMESSAGE("MiniMessage"),
	/** A JSON text component ({@code {"text":"Hi","color":"gold"}}), as used by commands and data packs. */
	JSON("JSON");

	public final String displayName;

	TextFormat(String displayName) {
		this.displayName = displayName;
	}
}
