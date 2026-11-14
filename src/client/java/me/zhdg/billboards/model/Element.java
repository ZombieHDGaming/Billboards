package me.zhdg.billboards.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One overlay. Static settings live here; animated values come from the element's keyframe tracks on Flashback's
 * timeline, falling back to the {@code base*} values when a channel has no keyframes.
 */
public class Element {

	public UUID id = UUID.randomUUID();
	public String name = "Element";
	public ElementType type = ElementType.IMAGE;
	public Space space = Space.WORLD;

	/** The group this element is in, or null at the top level. Its transform is then relative to the group. */
	public @Nullable UUID parentId = null;

	// --- Placement ---
	public Facing facing = Facing.FIXED;
	/** World only: when false the element is drawn on top of blocks and entities. */
	public boolean depthTest = true;
	/** Point inside the element (0-1) that sits at its position and that it rotates/scales around. */
	public float pivotX = 0.5f;
	public float pivotY = 0.5f;
	/** Draw order: higher layers draw later (on top). World elements with depth testing are sorted by distance instead. */
	public int layer = 0;
	/** Hidden in the editor and in exports, without touching keyframes. */
	public boolean hidden = false;

	// --- Entity attachment (world only) ---
	/** When set, the transform position is an offset from this entity's anchor instead of a world position. */
	public @Nullable UUID attachEntity = null;
	/** Shown in the editor when the entity isn't loaded; not used to find the entity. */
	public @Nullable String attachEntityName = null;
	public AttachAnchor attachAnchor = AttachAnchor.ABOVE_HEAD;
	/** Turn the offset (and a fixed-facing element) with the entity's body rotation. */
	public boolean attachRotate = false;

	// --- Size ---
	/** World: blocks. Screen: fraction of the frame height. For text this is ignored; see {@link #fontSize}. */
	public float width = 1.0f;
	public float height = 1.0f;

	// --- Image ---
	public @Nullable String assetId = null;
	public boolean pixelated = false;

	// --- Animated images (GIF/APNG) and videos ---
	/** Replay tick at which playback starts (the media's first frame shows before it). */
	public int mediaStartTick = 0;
	/** Playback speed; 1 plays at the media's own speed when the replay runs at normal speed. */
	public float mediaSpeed = 1;
	/** Start again from the beginning after the end, instead of holding the last frame. */
	public boolean mediaLoop = true;

	// --- Text ---
	/** Height of one line of text. World: blocks. Screen: fraction of the frame height. */
	public float fontSize = 0.25f;
	public TextAlign align = TextAlign.CENTER;
	public boolean textShadow = true;
	/** ARGB colour behind the text; 0 for none. */
	public int backgroundColor = 0;

	/** Asset id of a TTF/OTF font file, or null for Minecraft's font (which follows resource packs). */
	public @Nullable String fontAssetId = null;

	// --- Shape ---
	public ShapeKind shapeKind = ShapeKind.ROUNDED_RECTANGLE;
	/** ARGB fill colour; with a gradient, the colour at the left / top. */
	public int fillColor = 0xCC1E1E28;
	/** ARGB colour at the right / bottom of a gradient. */
	public int fillColor2 = 0xCC3C3C55;
	public Gradient gradient = Gradient.NONE;
	/** ARGB outline colour. */
	public int strokeColor = 0xFFFFFFFF;
	/** Outline width (line/arrow thickness), in the same units as the size; 0 for no outline. */
	public float strokeWidth = 0;
	/** Corner radius of rounded rectangles, in the same units as the size. */
	public float cornerRadius = 0.15f;

	// --- Look ---
	public BlendMode blend = BlendMode.NORMAL;
	public Effects effects = new Effects();

	// --- Base (un-animated) values ---
	public ChannelValues transform = new ChannelValues(Channel.TRANSFORM);
	public ChannelValues appearance = new ChannelValues(Channel.APPEARANCE);
	public ChannelValues content = new ChannelValues(Channel.CONTENT);

	public ChannelValues base(Channel channel) {
		return switch (channel) {
			case TRANSFORM -> this.transform;
			case APPEARANCE -> this.appearance;
			case CONTENT -> this.content;
		};
	}

	public void setBase(Channel channel, ChannelValues values) {
		switch (channel) {
			case TRANSFORM -> this.transform = values;
			case APPEARANCE -> this.appearance = values;
			case CONTENT -> this.content = values;
		}
	}

	/** Fills in anything missing after loading an older or hand-edited project file. */
	public void validate() {
		if (this.id == null) this.id = UUID.randomUUID();
		if (this.name == null) this.name = "Element";
		if (this.type == null) this.type = ElementType.IMAGE;
		if (this.space == null) this.space = Space.WORLD;
		if (this.facing == null) this.facing = Facing.FIXED;
		if (this.align == null) this.align = TextAlign.CENTER;
		if (this.attachAnchor == null) this.attachAnchor = AttachAnchor.ABOVE_HEAD;
		if (this.shapeKind == null) this.shapeKind = ShapeKind.ROUNDED_RECTANGLE;
		if (this.gradient == null) this.gradient = Gradient.NONE;
		if (this.blend == null) this.blend = BlendMode.NORMAL;
		if (this.effects == null) this.effects = new Effects();
		if (this.transform == null) this.transform = new ChannelValues(Channel.TRANSFORM);
		if (this.appearance == null) this.appearance = new ChannelValues(Channel.APPEARANCE);
		if (this.content == null) this.content = new ChannelValues(Channel.CONTENT);
		if (this.content.text == null) this.content.text = "";
	}

	public Element copy() {
		Element copy = new Element();
		copy.id = UUID.randomUUID();
		copy.name = this.name;
		copy.type = this.type;
		copy.space = this.space;
		copy.facing = this.facing;
		copy.depthTest = this.depthTest;
		copy.pivotX = this.pivotX;
		copy.pivotY = this.pivotY;
		copy.layer = this.layer;
		copy.hidden = this.hidden;
		copy.attachEntity = this.attachEntity;
		copy.attachEntityName = this.attachEntityName;
		copy.attachAnchor = this.attachAnchor;
		copy.attachRotate = this.attachRotate;
		copy.width = this.width;
		copy.height = this.height;
		copy.assetId = this.assetId;
		copy.pixelated = this.pixelated;
		copy.fontSize = this.fontSize;
		copy.align = this.align;
		copy.textShadow = this.textShadow;
		copy.backgroundColor = this.backgroundColor;
		copy.parentId = this.parentId;
		copy.blend = this.blend;
		copy.effects = this.effects.copy();
		copy.mediaStartTick = this.mediaStartTick;
		copy.mediaSpeed = this.mediaSpeed;
		copy.mediaLoop = this.mediaLoop;
		copy.fontAssetId = this.fontAssetId;
		copy.shapeKind = this.shapeKind;
		copy.fillColor = this.fillColor;
		copy.fillColor2 = this.fillColor2;
		copy.gradient = this.gradient;
		copy.strokeColor = this.strokeColor;
		copy.strokeWidth = this.strokeWidth;
		copy.cornerRadius = this.cornerRadius;
		copy.transform = this.transform.copy();
		copy.appearance = this.appearance.copy();
		copy.content = this.content.copy();
		return copy;
	}
}
