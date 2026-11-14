package me.zhdg.billboards.model;

public enum ShapeKind {
	RECTANGLE("Rectangle"),
	ROUNDED_RECTANGLE("Rounded rectangle"),
	ELLIPSE("Ellipse"),
	/** A line across the shape's width; the outline width is its thickness. */
	LINE("Line"),
	/** An arrow pointing right across the shape's width; the height is the head's width, the outline width the shaft's. */
	ARROW("Arrow");

	public final String displayName;

	ShapeKind(String displayName) {
		this.displayName = displayName;
	}
}
