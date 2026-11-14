package me.zhdg.billboards.model;

/** How a world-space element is oriented relative to the camera. */
public enum Facing {
	/** Uses only the element's own rotation, like a poster on a wall. */
	FIXED("Fixed"),
	/** Always turns to face the camera (rotation is applied on top, so roll still works). */
	FACE_CAMERA("Face camera"),
	/** Turns around the vertical axis only, like a standing sign. */
	Y_AXIS("Face camera (Y axis)");

	public final String displayName;

	Facing(String displayName) {
		this.displayName = displayName;
	}
}
