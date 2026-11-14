package me.zhdg.billboards.model;

/** How an element's colours combine with what is behind it. */
public enum BlendMode {
	NORMAL("Normal"),
	/** Adds light: good for glows, sparks and light beams. */
	ADDITIVE("Additive"),
	/** Darkens: white leaves the background unchanged, black makes it black. */
	MULTIPLY("Multiply"),
	/** Lightens: black leaves the background unchanged. */
	SCREEN("Screen");

	public final String displayName;

	BlendMode(String displayName) {
		this.displayName = displayName;
	}
}
