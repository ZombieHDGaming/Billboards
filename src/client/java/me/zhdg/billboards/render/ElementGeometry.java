package me.zhdg.billboards.render;

import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.ElementType;
import me.zhdg.billboards.model.Facing;
import me.zhdg.billboards.project.ProjectManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Where an element is and how big it is. Rendering, viewport picking, gizmos and selection outlines all go through
 * here so they always agree.
 * <p>
 * Every element is laid out in a local frame where +X is right and +Y is up as seen from the front, with the
 * element's pivot at the origin. Sizes are in blocks.
 */
public final class ElementGeometry {

	/** Size of one font pixel in blocks, as on vanilla text displays. */
	public static final float TEXT_PIXEL = 0.025f;
	/** Distance between lines in font pixels (9 for the glyphs plus 1), as on vanilla text displays. */
	public static final int LINE_HEIGHT = 10;

	/** An axis-aligned rectangle in an element's local frame. */
	public record Bounds(float minX, float minY, float maxX, float maxY) {
		public boolean contains(float x, float y) {
			return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY;
		}
	}

	/**
	 * Text laid out like a vanilla text display, in font pixels with y growing downwards: line {@code i} starts at
	 * y = {@code i * LINE_HEIGHT}, and the background covers (-1, -1) to ({@code width}, {@code height}).
	 *
	 * @param lines     the wrapped lines (already cut short by the reveal channel)
	 * @param lineWidths each line's full width (before the reveal), so revealed text doesn't shift
	 * @param width     the widest line
	 * @param font      the custom font, or null for Minecraft's font
	 * @param fontScale custom fonts: font pixels per raster pixel of the font (its line height maps to 9 pixels)
	 */
	public record TextLayout(List<FormattedCharSequence> lines, float[] lineWidths, float width, @Nullable CustomFont font, float fontScale) {
		public float height() {
			return this.lines.size() * LINE_HEIGHT - 1;
		}

		/** Left edge of a line, in font pixels. */
		public float lineX(int line, me.zhdg.billboards.model.TextAlign align) {
			float lineWidth = this.lineWidths[line];
			return switch (align) {
				case LEFT -> 0;
				case CENTER -> this.width / 2f - lineWidth / 2f;
				case RIGHT -> this.width - lineWidth;
			};
		}

		/** Where the element's pivot is, in font pixels. */
		public float pivotX(Element element) {
			return -1 + element.pivotX * (this.width + 1);
		}

		public float pivotY(Element element) {
			return -1 + element.pivotY * (this.height() + 1);
		}
	}

	private ElementGeometry() {
	}

	// ------------------------------------------------------------------------------------------------
	// Local bounds
	// ------------------------------------------------------------------------------------------------

	/** The element's visible rectangle in its local frame, or null if it currently draws nothing. */
	public static @Nullable Bounds localBounds(Element element, ChannelValues content) {
		if (element.type == ElementType.GROUP) {
			return null;
		}
		if (element.type.isPicture() || element.type == ElementType.SHAPE) {
			boolean crop = element.type.isPicture();
			float u0 = crop ? clamp01(content.getFloat(Channel.CROP_LEFT)) : 0;
			float v0 = crop ? clamp01(content.getFloat(Channel.CROP_TOP)) : 0;
			float u1 = crop ? clamp01(content.getFloat(Channel.CROP_RIGHT)) : 1;
			float v1 = crop ? clamp01(content.getFloat(Channel.CROP_BOTTOM)) : 1;
			if (u1 <= u0 || v1 <= v0) {
				return null;
			}
			float width = element.width;
			float height = element.height;
			// Cropping trims the quad but keeps the remaining pixels where they were.
			return new Bounds((u0 - element.pivotX) * width, (element.pivotY - v1) * height,
				(u1 - element.pivotX) * width, (element.pivotY - v0) * height);
		}

		TextLayout layout = textLayout(element, content);
		if (layout == null) {
			return null;
		}
		float width = (layout.width() + 1) * TEXT_PIXEL;
		float height = (layout.height() + 1) * TEXT_PIXEL;
		return new Bounds(-element.pivotX * width, (element.pivotY - 1) * height, (1 - element.pivotX) * width, element.pivotY * height);
	}

	// ------------------------------------------------------------------------------------------------
	// Text
	// ------------------------------------------------------------------------------------------------

	public static @Nullable TextLayout textLayout(Element element, ChannelValues content) {
		String text = content.text == null ? "" : content.text;
		double reveal = content.get(Channel.REVEAL);
		if (text.isEmpty() || reveal <= 0) {
			return null;
		}

		CustomFont custom = CustomFont.get(ProjectManager.currentReplayId(), element.fontAssetId);
		float fontScale = 1;
		StringSplitter splitter;
		if (custom != null) {
			float scale = 9f / custom.lineHeight();
			fontScale = scale;
			splitter = new StringSplitter((codePoint, style) -> custom.glyph(codePoint).advance() * scale);
		} else {
			splitter = Minecraft.getInstance().font.getSplitter();
		}

		// What Font.split does, with the splitter of whichever font is used.
		List<FormattedCharSequence> lines = Language.getInstance().getVisualOrder(
			splitter.splitLines(parse(element, text), Math.max(1, element.lineWidth), Style.EMPTY));
		if (lines.isEmpty()) {
			return null;
		}
		float[] widths = new float[lines.size()];
		float width = 0;
		int total = 0;
		for (int i = 0; i < widths.length; i++) {
			widths[i] = splitter.stringWidth(lines.get(i));
			width = Math.max(width, widths[i]);
			total += length(lines.get(i));
		}
		if (total == 0) {
			return null;
		}

		if (reveal < 1) {
			int remaining = (int) Math.floor(total * reveal);
			if (remaining <= 0) {
				return null;
			}
			List<FormattedCharSequence> shown = new java.util.ArrayList<>(lines.size());
			for (FormattedCharSequence line : lines) {
				shown.add(truncate(line, remaining));
				remaining = Math.max(0, remaining - length(line));
			}
			lines = shown;
		}
		return new TextLayout(lines, widths, width, custom, fontScale);
	}

	/** Text content as a chat component, read as the element's {@link Element#textFormat}. See {@link RichText}. */
	public static Component parse(Element element, String text) {
		return RichText.parse(text, element.textFormat);
	}

	/** Number of characters (code points) in a line. */
	public static int length(FormattedCharSequence line) {
		int[] count = {0};
		line.accept((index, style, codePoint) -> {
			count[0]++;
			return true;
		});
		return count[0];
	}

	/** The first {@code max} characters of a line. */
	public static FormattedCharSequence truncate(FormattedCharSequence line, int max) {
		if (max <= 0) {
			return FormattedCharSequence.EMPTY;
		}
		return sink -> {
			int[] count = {0};
			return line.accept((index, style, codePoint) -> count[0]++ < max && sink.accept(index, style, codePoint));
		};
	}

	/** A line without its colours (keeping bold, italic and fonts), for single-colour silhouettes. */
	public static FormattedCharSequence uncoloured(FormattedCharSequence line) {
		return sink -> line.accept((index, style, codePoint) -> sink.accept(index, style.withColor((net.minecraft.network.chat.TextColor) null), codePoint));
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
	 * Camera-relative model matrix of a top-level element (multiply by the camera's view rotation to get view space):
	 * position, then orientation, then scale. Null if the element can't be placed.
	 */
	public static @Nullable Matrix4f worldModel(Element element, ChannelValues transform, OverlayRenderer.SceneCamera camera) {
		Vec3 position = worldPosition(element, transform);
		if (position == null) {
			return null;
		}
		Vec3 relative = position.subtract(camera.position());
		return new Matrix4f()
			.translate((float) relative.x, (float) relative.y, (float) relative.z)
			.rotate(orientation(element, transform, camera, attachYaw(element)))
			.scale(transform.getFloat(Channel.SCALE_X), transform.getFloat(Channel.SCALE_Y), 1);
	}

	/**
	 * An element's orientation with its billboard mode applied, using the same maths as vanilla display entities.
	 * Axes that follow the camera ignore the element's own rotation around them.
	 *
	 * @param attachYaw degrees the element turns with an entity it is attached to
	 */
	public static Quaternionf orientation(Element element, ChannelValues transform, OverlayRenderer.SceneCamera camera, float attachYaw) {
		float yaw = (float) Math.toRadians(transform.get(Channel.YAW));
		float pitch = (float) Math.toRadians(transform.get(Channel.PITCH));
		float roll = (float) Math.toRadians(transform.get(Channel.ROLL));
		float attach = (float) Math.toRadians(-attachYaw);
		return switch (element.facing) {
			case FIXED -> new Quaternionf().rotateY(attach).rotateY(yaw).rotateX(pitch).rotateZ(roll);
			case HORIZONTAL -> new Quaternionf().rotateY(attach).rotateY(yaw).rotateX(cameraPitch(camera)).rotateZ(roll);
			case VERTICAL -> new Quaternionf().rotateY(cameraYaw(camera)).rotateX(pitch).rotateZ(roll);
			case CENTER -> new Quaternionf().rotateY(cameraYaw(camera)).rotateX(cameraPitch(camera)).rotateZ(roll);
		};
	}

	/**
	 * The part of {@link #orientation} that comes from the camera and the entity: the element's own rotation is
	 * {@code inverse(prefix) * orientation}, read as yaw/pitch/roll (YXZ order) for the axes the mode leaves to it.
	 */
	public static Quaternionf orientationPrefix(Facing facing, OverlayRenderer.SceneCamera camera, float attachYaw) {
		float attach = (float) Math.toRadians(-attachYaw);
		return switch (facing) {
			case FIXED, HORIZONTAL -> new Quaternionf().rotateY(attach);
			case VERTICAL -> new Quaternionf().rotateY(cameraYaw(camera));
			case CENTER -> new Quaternionf().rotateY(cameraYaw(camera)).rotateX(cameraPitch(camera));
		};
	}

	/** Vanilla billboards turn to {@code 180° - camera yaw} around Y... */
	private static float cameraYaw(OverlayRenderer.SceneCamera camera) {
		return (float) Math.toRadians(180 - camera.yRot());
	}

	/** ...and {@code -camera pitch} around X. */
	private static float cameraPitch(OverlayRenderer.SceneCamera camera) {
		return (float) Math.toRadians(-camera.xRot());
	}

	/** Element rotation: yaw around Y, then pitch around X, then roll around Z (degrees). */
	public static Quaternionf rotation(ChannelValues transform) {
		return new Quaternionf()
			.rotateY((float) Math.toRadians(transform.get(Channel.YAW)))
			.rotateX((float) Math.toRadians(transform.get(Channel.PITCH)))
			.rotateZ((float) Math.toRadians(transform.get(Channel.ROLL)));
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
