package me.zhdg.billboards.model;

public enum Gradient {
	NONE("Solid"),
	HORIZONTAL("Left to right"),
	VERTICAL("Top to bottom");

	public final String displayName;

	Gradient(String displayName) {
		this.displayName = displayName;
	}
}
