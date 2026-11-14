package me.zhdg.billboards.model;

import com.google.gson.annotations.SerializedName;

/**
 * How an element turns towards the camera; the same modes as vanilla display entities' {@code billboard}.
 * Axes that follow the camera ignore the element's own rotation around them; roll always applies.
 */
public enum Facing {
	/** Uses only the element's own rotation, like a poster on a wall. */
	FIXED("Fixed"),
	/** Turns around the vertical axis to face the camera, like a standing sign (keeps its own pitch). */
	@SerializedName(value = "VERTICAL", alternate = "Y_AXIS")
	VERTICAL("Vertical (turn to camera)"),
	/** Tilts around its horizontal axis to face the camera (keeps its own yaw). */
	HORIZONTAL("Horizontal (tilt to camera)"),
	/** Always faces the camera, like a name tag. */
	@SerializedName(value = "CENTER", alternate = "FACE_CAMERA")
	CENTER("Center (face camera)");

	public final String displayName;

	Facing(String displayName) {
		this.displayName = displayName;
	}
}
