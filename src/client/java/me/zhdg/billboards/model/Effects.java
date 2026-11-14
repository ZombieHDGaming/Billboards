package me.zhdg.billboards.model;

/**
 * Optional effects drawn with an element. Distances are in the element's size units (blocks in the world, fractions
 * of the frame height on screen).
 */
public class Effects {

	public boolean shadow = false;
	/** ARGB; the alpha is the shadow's strength. */
	public int shadowColor = 0xB0000000;
	/** Offset to the right and down, as seen on screen. */
	public float shadowOffsetX = 0.006f;
	public float shadowOffsetY = 0.006f;
	/** How far the shadow's edge spreads; 0 for a hard shadow. */
	public float shadowSoftness = 0.004f;

	public boolean outline = false;
	public int outlineColor = 0xFF000000;
	public float outlineWidth = 0.004f;

	public boolean glow = false;
	public int glowColor = 0xFFFFE680;
	public float glowRadius = 0.02f;

	/** Blur radius for images and videos; 0 for none. */
	public float blur = 0;

	public Effects copy() {
		Effects copy = new Effects();
		copy.shadow = this.shadow;
		copy.shadowColor = this.shadowColor;
		copy.shadowOffsetX = this.shadowOffsetX;
		copy.shadowOffsetY = this.shadowOffsetY;
		copy.shadowSoftness = this.shadowSoftness;
		copy.outline = this.outline;
		copy.outlineColor = this.outlineColor;
		copy.outlineWidth = this.outlineWidth;
		copy.glow = this.glow;
		copy.glowColor = this.glowColor;
		copy.glowRadius = this.glowRadius;
		copy.blur = this.blur;
		return copy;
	}

	public boolean any() {
		return this.shadow || this.outline || this.glow || this.blur > 0;
	}
}
