package me.zhdg.billboards.model;

/**
 * Where an element lives.
 * <ul>
 *     <li>{@link #WORLD}: placed at world coordinates; position is in blocks, size in blocks.</li>
 *     <li>{@link #SCREEN}: pinned to the exported frame; position is a 0-1 fraction of the frame (0,0 = top-left),
 *     size is a fraction of the frame <em>height</em> so shapes keep their aspect ratio at any resolution.</li>
 * </ul>
 */
public enum Space {
	WORLD("World"),
	SCREEN("Screen");

	public final String displayName;

	Space(String displayName) {
		this.displayName = displayName;
	}
}
