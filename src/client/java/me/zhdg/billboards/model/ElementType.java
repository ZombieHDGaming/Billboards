package me.zhdg.billboards.model;

public enum ElementType {
	IMAGE("Image"),
	TEXT("Text");

	public final String displayName;

	ElementType(String displayName) {
		this.displayName = displayName;
	}
}
