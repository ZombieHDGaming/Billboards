package me.zhdg.billboards.keyframe;

import me.zhdg.billboards.Lang;
import com.moulberry.flashback.keyframe.KeyframeRegistry;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import me.zhdg.billboards.model.Channel;
import org.jetbrains.annotations.Nullable;

/**
 * The three Flashback keyframe types Billboards adds, one per {@link Channel}.
 * <p>
 * {@link KeyframeRegistry#register} de-duplicates by Java class, so each channel needs its own subclass.
 */
public final class BillboardKeyframeTypes {

	public static final String ID_PREFIX = "billboards:";

	public static final Type TRANSFORM = new TransformType();
	public static final Type APPEARANCE = new AppearanceType();
	public static final Type CONTENT = new ContentType();

	private BillboardKeyframeTypes() {
	}

	public static void register() {
		KeyframeRegistry.register(TRANSFORM);
		KeyframeRegistry.register(APPEARANCE);
		KeyframeRegistry.register(CONTENT);
	}

	public static Type of(Channel channel) {
		return switch (channel) {
			case TRANSFORM -> TRANSFORM;
			case APPEARANCE -> APPEARANCE;
			case CONTENT -> CONTENT;
		};
	}

	public static @Nullable Type byId(String id) {
		for (Channel channel : Channel.values()) {
			if (of(channel).id().equals(id)) {
				return of(channel);
			}
		}
		return null;
	}

	public abstract static sealed class Type implements KeyframeType<ChannelKeyframe> permits TransformType, AppearanceType, ContentType {
		public final Channel channel;

		Type(Channel channel) {
			this.channel = channel;
		}

		@Override
		public String name() {
			return Lang.tr("Billboard %s", Lang.tr(this.channel.displayName));
		}

		@Override
		public String id() {
			return ID_PREFIX + this.channel.id();
		}

		@Override
		public @Nullable ChannelKeyframe createDirect() {
			return null;
		}

		@Override
		public @Nullable KeyframeCreatePopup<ChannelKeyframe> createPopup() {
			return null;
		}

		@Override
		public Class<? extends KeyframeChange> keyframeChangeType() {
			return ChannelChange.class;
		}

		/** Flashback's handlers never apply our changes; see {@link KeyframeEvaluator}. */
		@Override
		public boolean supportsHandler(KeyframeHandler handler) {
			return false;
		}

		/** Tracks are created from the Billboards window, which ties them to an element. */
		@Override
		public boolean canBeCreatedNormally() {
			return false;
		}

		@Override
		public boolean allowApplyingDuplicateKeyframeChanges() {
			return true;
		}
	}

	static final class TransformType extends Type {
		TransformType() {
			super(Channel.TRANSFORM);
		}
	}

	static final class AppearanceType extends Type {
		AppearanceType() {
			super(Channel.APPEARANCE);
		}
	}

	static final class ContentType extends Type {
		ContentType() {
			super(Channel.CONTENT);
		}
	}
}
