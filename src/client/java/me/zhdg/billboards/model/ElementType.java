package me.zhdg.billboards.model;

public enum ElementType {
	IMAGE("Image"),
	TEXT("Text"),
	SHAPE("Shape"),
	VIDEO("Video"),
	/** Holds other elements: its transform and appearance apply to everything inside it. */
	GROUP("Group");

	public final String displayName;

	ElementType(String displayName) {
		this.displayName = displayName;
	}

	/** Images and videos: drawn from an asset as a textured quad. */
	public boolean isPicture() {
		return this == IMAGE || this == VIDEO;
	}

	/** Whether the Content channel (text, reveal, crop) means anything for this type. */
	public boolean hasContent() {
		return this == IMAGE || this == TEXT || this == VIDEO;
	}
}
