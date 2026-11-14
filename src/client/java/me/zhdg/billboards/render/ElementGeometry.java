package me.zhdg.billboards.render;

import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * Where an element is and how big it is. Rendering, viewport picking, gizmos and selection outlines all go through
 * here so they always agree.
 * <p>
 * Every element is laid out in a local frame where +X is right and +Y is up as seen from the front, with the
 * element's pivot at the origin and sizes multiplied by a "unit" (1 in the world, where sizes are blocks; the frame
 * height on screen).
 */
public final class ElementGeometry {

	/** An axis-aligned rectangle in an element's local frame. */
	public record Bounds(float minX, float minY, float maxX, float maxY) {
		public boolean contains(float x, float y) {
			return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY;
		}
	}

	/** Text laid out in font pixels: the shown lines, the block size, and the size of one font pixel in local units. */
	public record TextLayout(String[] lines, int blockWidth, int blockHeight, float pixel) {
	}

	private ElementGeometry() {
	}

	// ------------------------------------------------------------------------------------------------
	// Local bounds
	// ------------------------------------------------------------------------------------------------

	/** The element's visible rectangle in its local frame, or null if it currently draws nothing. */
	public static @Nullable Bounds localBounds(Element element, ChannelValues content, float unit) {
		if (element.type == ElementType.IMAGE) {
			float u0 = clamp01(content.getFloat(Channel.CROP_LEFT));
			float v0 = clamp01(content.getFloat(Channel.CROP_TOP));
			float u1 = clamp01(content.getFloat(Channel.CROP_RIGHT));
			float v1 = clamp01(content.getFloat(Channel.CROP_BOTTOM));
			if (u1 <= u0 || v1 <= v0) {
				return null;
			}
			float width = element.width * unit;
			float height = element.height * unit;
			// Cropping trims the quad but keeps the remaining pixels where they were.
			return new Bounds((u0 - element.pivotX) * width, (element.pivotY - v1) * height,
				(u1 - element.pivotX) * width, (element.pivotY - v0) * height);
		}

		TextLayout layout = textLayout(element, content, unit);
		if (layout == null) {
			return null;
		}
		float width = layout.blockWidth() * layout.pixel();
		float height = layout.blockHeight() * layout.pixel();
		return new Bounds(-element.pivotX * width, (element.pivotY - 1) * height, (1 - element.pivotX) * width, element.pivotY * height);
	}

	public static @Nullable TextLayout textLayout(Element element, ChannelValues content, float unit) {
		String text = revealed(content);
		if (text.isEmpty()) {
			return null;
		}
		Font font = Minecraft.getInstance().font;
		String[] lines = text.split("\n", -1);
		int blockWidth = 0;
		for (String line : lines) {
			blockWidth = Math.max(blockWidth, font.width(line));
		}
		if (blockWidth == 0) {
			return null;
		}
		// fontSize is the height of one line.
		float pixel = element.fontSize * unit / font.lineHeight;
		return new TextLayout(lines, blockWidth, lines.length * font.lineHeight, pixel);
	}

	/** The part of the text shown by the reveal fraction, counting code points (newlines don't count). */
	public static String revealed(ChannelValues content) {
		String text = content.text == null ? "" : content.text;
		double reveal = content.get(Channel.REVEAL);
		if (reveal >= 1) {
			return text;
		}
		if (reveal <= 0) {
			return "";
		}
		int visible = (int) text.codePoints().filter(c -> c != '\n').count();
		int shown = (int) Math.floor(visible * reveal);
		StringBuilder builder = new StringBuilder();
		int count = 0;
		for (int i = 0; i < text.length() && count < shown; ) {
			int codePoint = text.codePointAt(i);
			builder.appendCodePoint(codePoint);
			if (codePoint != '\n') {
				count++;
			}
			i += Character.charCount(codePoint);
		}
		return builder.toString();
	}

	// ------------------------------------------------------------------------------------------------
	// World placement
	// ------------------------------------------------------------------------------------------------

	/** The entity an element is attached to, or null if it isn't attached or the entity isn't loaded right now. */
	public static @Nullable Entity attachedEntity(Element element) {
		if (element.attachEntity == null) {
			return null;
		}
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? null : level.getEntity(element.attachEntity);
	}

	/** Whether the element should be drawn at all: attached elements disappear while their entity isn't loaded. */
	public static boolean isPlaced(Element element) {
		return element.attachEntity == null || attachedEntity(element) != null;
	}

	/** The point an attached element's offset is measured from, at this frame's partial tick. */
	public static @Nullable Vec3 anchorPosition(Element element, Entity entity) {
		float partial = partialTick();
		Vec3 feet = entity.getPosition(partial);
		return switch (element.attachAnchor) {
			case FEET -> feet;
			case CENTER -> feet.add(0, entity.getBbHeight() / 2.0, 0);
			case EYES -> entity.getEyePosition(partial);
			case ABOVE_HEAD -> feet.add(0, entity.getBbHeight() + 0.5, 0);
		};
	}

	/** Degrees the attached element turns with its entity (0 when not attached or not rotating with it). */
	public static float attachYaw(Element element) {
		if (!element.attachRotate) {
			return 0;
		}
		Entity entity = attachedEntity(element);
		return entity == null ? 0 : entity.getPreciseBodyRotation(partialTick());
	}

	/** Where the element's pivot is in the world, or null if it is attached to an entity that isn't loaded. */
	public static @Nullable Vec3 worldPosition(Element element, ChannelValues transform) {
		double x = transform.get(Channel.X);
		double y = transform.get(Channel.Y);
		double z = transform.get(Channel.Z);
		if (element.attachEntity == null) {
			return new Vec3(x, y, z);
		}
		Entity entity = attachedEntity(element);
		if (entity == null) {
			return null;
		}
		Vec3 anchor = anchorPosition(element, entity);
		Vec3 offset = new Vec3(x, y, z);
		if (element.attachRotate) {
			// Minecraft yaw θ faces (-sin θ, 0, cos θ); rotating by -θ turns the offset's +Z to the entity's front.
			offset = offset.yRot((float) Math.toRadians(-entity.getPreciseBodyRotation(partialTick())));
		}
		return anchor.add(offset);
	}

	/**
	 * Converts a world position back into the element's transform position (an entity offset if attached).
	 * Returns null if the element is attached to an entity that isn't loaded.
	 */
	public static double @Nullable [] transformPositionFor(Element element, Vec3 world) {
		if (element.attachEntity == null) {
			return new double[]{world.x, world.y, world.z};
		}
		Entity entity = attachedEntity(element);
		if (entity == null) {
			return null;
		}
		Vec3 offset = world.subtract(anchorPosition(element, entity));
		if (element.attachRotate) {
			offset = offset.yRot((float) Math.toRadians(entity.getPreciseBodyRotation(partialTick())));
		}
		return new double[]{offset.x, offset.y, offset.z};
	}

	/**
	 * Camera-relative model matrix of a world element (multiply by the camera's view rotation to get view space):
	 * position, then facing, then the element's own rotation and scale. Null if the element can't be placed.
	 */
	public static @Nullable Matrix4f worldModel(Element element, ChannelValues transform, Vec3 cameraPos, Quaternionfc cameraOrientation) {
		Vec3 position = worldPosition(element, transform);
		if (position == null) {
			return null;
		}
		double dx = position.x - cameraPos.x;
		double dy = position.y - cameraPos.y;
		double dz = position.z - cameraPos.z;

		Matrix4f matrix = new Matrix4f().translate((float) dx, (float) dy, (float) dz);
		switch (element.facing) {
			case FACE_CAMERA -> matrix.rotate(cameraOrientation);
			// The element's front (+Z) points at the camera, rotating around Y only.
			case Y_AXIS -> matrix.rotateY((float) Math.atan2(-dx, -dz));
			case FIXED -> matrix.rotateY((float) Math.toRadians(-attachYaw(element)));
		}
		matrix.rotate(rotation(transform));
		matrix.scale(transform.getFloat(Channel.SCALE_X), transform.getFloat(Channel.SCALE_Y), 1);
		return matrix;
	}

	/** Element rotation: yaw around Y, then pitch around X, then roll around Z (degrees). */
	public static Quaternionf rotation(ChannelValues transform) {
		return new Quaternionf()
			.rotateY((float) Math.toRadians(transform.get(Channel.YAW)))
			.rotateX((float) Math.toRadians(transform.get(Channel.PITCH)))
			.rotateZ((float) Math.toRadians(transform.get(Channel.ROLL)));
	}

	// ------------------------------------------------------------------------------------------------
	// Screen placement
	// ------------------------------------------------------------------------------------------------

	/** Model matrix of a screen element in frame pixels (origin top-left, y down) for a frame of the given size. */
	public static Matrix4f screenModel(ChannelValues transform, float width, float height) {
		return new Matrix4f()
			.translate(transform.getFloat(Channel.X) * width, transform.getFloat(Channel.Y) * height, 0)
			.scale(1, -1, 1) // screen pixels grow downwards; the local frame is y-up
			.rotateZ((float) Math.toRadians(transform.get(Channel.ROLL)))
			.scale(transform.getFloat(Channel.SCALE_X), transform.getFloat(Channel.SCALE_Y), 1);
	}

	// ------------------------------------------------------------------------------------------------

	/** The partial tick the level is being rendered at this frame (also correct during exports). */
	public static float partialTick() {
		return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.worldPartialTicks;
	}

	private static float clamp01(float value) {
		return Math.max(0, Math.min(1, value));
	}
}
