package me.zhdg.billboards.model;

import java.util.Locale;

/**
 * A group of keyframeable properties. Each element can have one Flashback keyframe track per channel.
 * Values are stored as a flat {@code double[]} whose layout is described by {@link #components}, so
 * interpolation can be done component-wise in one place.
 */
public enum Channel {
	TRANSFORM("Transform",
		new String[]{"x", "y", "z", "pitch", "yaw", "roll", "scale_x", "scale_y"},
		new double[]{0, 0, 0, 0, 0, 0, 1, 1},
		new boolean[]{false, false, false, false, false, false, false, false}),
	APPEARANCE("Appearance",
		new String[]{"opacity", "red", "green", "blue", "visible"},
		new double[]{1, 1, 1, 1, 1},
		new boolean[]{false, false, false, false, true}),
	CONTENT("Content",
		new String[]{"reveal", "crop_left", "crop_top", "crop_right", "crop_bottom"},
		new double[]{1, 0, 0, 1, 1},
		new boolean[]{false, false, false, false, false});

	// Component indices, kept as constants so callers don't index by magic numbers.
	public static final int X = 0, Y = 1, Z = 2, PITCH = 3, YAW = 4, ROLL = 5, SCALE_X = 6, SCALE_Y = 7;
	public static final int OPACITY = 0, RED = 1, GREEN = 2, BLUE = 3, VISIBLE = 4;
	public static final int REVEAL = 0, CROP_LEFT = 1, CROP_TOP = 2, CROP_RIGHT = 3, CROP_BOTTOM = 4;

	public final String displayName;
	public final String[] components;
	private final double[] defaults;
	/** Components that never interpolate: they hold the earlier keyframe's value until the next keyframe. */
	public final boolean[] step;

	Channel(String displayName, String[] components, double[] defaults, boolean[] step) {
		this.displayName = displayName;
		this.components = components;
		this.defaults = defaults;
		this.step = step;
	}

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public int size() {
		return this.components.length;
	}

	public double[] defaults() {
		return this.defaults.clone();
	}

	/** Whether this channel also carries a text string (only {@link #CONTENT}). */
	public boolean hasText() {
		return this == CONTENT;
	}

	public static Channel byId(String id) {
		return Channel.valueOf(id.toUpperCase(Locale.ROOT));
	}
}
