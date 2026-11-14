package me.zhdg.billboards.keyframe;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.spline.CatmullRom;
import com.moulberry.flashback.spline.Hermite;
import me.zhdg.billboards.editor.ChannelEditor;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A keyframe holding every value of one {@link Channel} for one element.
 */
public class ChannelKeyframe extends Keyframe {

	public final Channel channel;
	public final UUID elementId;
	public final ChannelValues values;

	public ChannelKeyframe(UUID elementId, ChannelValues values, InterpolationType interpolationType) {
		this.channel = values.channel;
		this.elementId = elementId;
		this.values = values;
		this.interpolationType(interpolationType);
	}

	@Override
	public KeyframeType<?> keyframeType() {
		return BillboardKeyframeTypes.of(this.channel);
	}

	@Override
	public Keyframe copy() {
		return new ChannelKeyframe(this.elementId, this.values.copy(), this.interpolationType());
	}

	@Override
	public KeyframeChange createChange() {
		return new ChannelChange(this.elementId, this.values.copy());
	}

	/** Catmull-Rom through four keyframes; {@code this} is the keyframe before {@code p1}. */
	@Override
	public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
		ChannelValues v0 = this.values;
		ChannelValues v1 = ((ChannelKeyframe) p1).values;
		ChannelValues v2 = ((ChannelKeyframe) p2).values;
		ChannelValues v3 = ((ChannelKeyframe) p3).values;
		float time1 = t1 - t0;
		float time2 = t2 - t0;
		float time3 = t3 - t0;

		ChannelValues out = v1.copy();
		int start = 0;
		if (this.channel == Channel.TRANSFORM) {
			// Positions can be large world coordinates, so interpolate them in double precision.
			Vector3d position = CatmullRom.position(xyz(v0), xyz(v1), xyz(v2), xyz(v3), time1, time2, time3, amount);
			out.set(Channel.X, position.x);
			out.set(Channel.Y, position.y);
			out.set(Channel.Z, position.z);
			start = 3;
		}
		for (int i = start; i < this.channel.size(); i++) {
			if (!this.channel.step[i]) {
				out.set(i, CatmullRom.value(v0.getFloat(i), v1.getFloat(i), v2.getFloat(i), v3.getFloat(i), time1, time2, time3, amount));
			}
		}
		return new ChannelChange(this.elementId, out);
	}

	/** Hermite spline through all keyframes in {@code keyframes} (keyed by real-time tick); {@code this} is the keyframe at or before {@code tick}. */
	@Override
	public KeyframeChange createHermiteInterpolatedChange(Map<Float, Keyframe> keyframes, float tick) {
		ChannelValues out = this.values.copy();
		int start = 0;
		if (this.channel == Channel.TRANSFORM) {
			Map<Float, Vector3d> positions = new HashMap<>();
			keyframes.forEach((time, keyframe) -> positions.put(time, xyz(((ChannelKeyframe) keyframe).values)));
			Vector3d position = Hermite.position(positions, tick);
			out.set(Channel.X, position.x);
			out.set(Channel.Y, position.y);
			out.set(Channel.Z, position.z);
			start = 3;
		}
		for (int i = start; i < this.channel.size(); i++) {
			if (this.channel.step[i]) {
				continue;
			}
			Map<Float, Double> component = new TreeMap<>();
			int index = i;
			keyframes.forEach((time, keyframe) -> component.put(time, ((ChannelKeyframe) keyframe).values.get(index)));
			out.set(i, Hermite.value(component, tick));
		}
		return new ChannelChange(this.elementId, out);
	}

	@Override
	public void renderEditKeyframe(Consumer<Consumer<Keyframe>> update) {
		// Flashback applies the update to every selected keyframe of this class, which can include other channels.
		Channel channel = this.channel;
		ChannelEditor.render(this.values,
			(component, value) -> update.accept(keyframe -> {
				if (keyframe instanceof ChannelKeyframe other && other.channel == channel) {
					other.values.set(component, value);
				}
			}),
			text -> update.accept(keyframe -> {
				if (keyframe instanceof ChannelKeyframe other && other.channel == channel) {
					other.values.text = text;
				}
			}));
	}

	private static Vector3d xyz(ChannelValues values) {
		return new Vector3d(values.get(Channel.X), values.get(Channel.Y), values.get(Channel.Z));
	}

	// --- Serialization (called from the Keyframe.TypeAdapter mixin) ---

	public JsonObject toJson(JsonSerializationContext context) {
		JsonObject object = new JsonObject();
		object.addProperty("type", BillboardKeyframeTypes.of(this.channel).id());
		object.addProperty("element", this.elementId.toString());
		object.add("values", this.values.toJson());
		object.add("interpolation_type", context.serialize(this.interpolationType()));
		return object;
	}

	public static ChannelKeyframe fromJson(Channel channel, JsonObject object, JsonDeserializationContext context) {
		UUID elementId = UUID.fromString(object.get("element").getAsString());
		ChannelValues values = ChannelValues.fromJson(channel, object.get("values"));
		JsonElement interpolation = object.get("interpolation_type");
		InterpolationType interpolationType = interpolation == null ? InterpolationType.getDefault()
			: context.deserialize(interpolation, InterpolationType.class);
		return new ChannelKeyframe(elementId, values, interpolationType);
	}
}
