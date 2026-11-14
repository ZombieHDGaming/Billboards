package me.zhdg.billboards.model;

/** How bright an element is drawn. */
public enum Lighting {
	/** Lit by the block and sky light where it is, like a vanilla text display (darker at night and in caves). */
	WORLD("World light"),
	/** Always at full brightness, showing its colours as they are. */
	FULL_BRIGHT("Full bright"),
	/** Fixed block and sky light levels, like a display entity's brightness override. */
	CUSTOM("Custom levels");

	public final String displayName;

	Lighting(String displayName) {
		this.displayName = displayName;
	}
}
