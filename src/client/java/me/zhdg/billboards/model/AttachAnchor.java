package me.zhdg.billboards.model;

/** The point on an entity that an attached element's position is measured from. */
public enum AttachAnchor {
	FEET("Feet"),
	CENTER("Centre"),
	EYES("Eyes"),
	/** Just above the head, where a name tag sits. */
	ABOVE_HEAD("Above head");

	public final String displayName;

	AttachAnchor(String displayName) {
		this.displayName = displayName;
	}
}
