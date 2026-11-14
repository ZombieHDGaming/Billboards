package me.zhdg.billboards.keyframe;

import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import me.zhdg.billboards.model.ChannelValues;

import java.util.UUID;

/**
 * The evaluated values of one channel of one element at some tick.
 * <p>
 * Flashback's handlers never apply these (our keyframe types report that no handler supports them); Billboards
 * evaluates its tracks itself in {@link KeyframeEvaluator}, reusing Flashback's interpolation via
 * {@code KeyframeTrack.createKeyframeChange}.
 */
public record ChannelChange(UUID elementId, ChannelValues values) implements KeyframeChange {

	@Override
	public void apply(KeyframeHandler keyframeHandler) {
	}

	@Override
	public KeyframeChange interpolate(KeyframeChange to, double amount) {
		ChannelChange other = (ChannelChange) to;
		return new ChannelChange(this.elementId, ChannelValues.lerp(this.values, other.values, amount));
	}
}
