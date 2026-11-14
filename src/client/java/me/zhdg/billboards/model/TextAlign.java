package me.zhdg.billboards.model;

public enum TextAlign {
	LEFT("Left"),
	CENTER("Center"),
	RIGHT("Right");

	public final String displayName;

	TextAlign(String displayName) {
		this.displayName = displayName;
	}
}
