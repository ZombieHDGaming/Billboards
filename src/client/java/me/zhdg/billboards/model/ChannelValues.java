package me.zhdg.billboards.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * The values of one {@link Channel}: a fixed-size vector of numbers plus, for {@link Channel#CONTENT}, a text string.
 * Instances are mutable; use {@link #copy()} before handing them to anything that stores them.
 */
public final class ChannelValues {

	public final Channel channel;
	public final double[] values;
	public @Nullable String text;

	public ChannelValues(Channel channel) {
		this(channel, channel.defaults(), channel.hasText() ? "" : null);
	}

	public ChannelValues(Channel channel, double[] values, @Nullable String text) {
		if (values.length != channel.size()) {
			throw new IllegalArgumentException("Expected " + channel.size() + " values for " + channel + ", got " + values.length);
		}
		this.channel = channel;
		this.values = values;
		this.text = text;
	}

	public double get(int component) {
		return this.values[component];
	}

	public float getFloat(int component) {
		return (float) this.values[component];
	}

	public void set(int component, double value) {
		this.values[component] = value;
	}

	public ChannelValues copy() {
		return new ChannelValues(this.channel, this.values.clone(), this.text);
	}

	/**
	 * Component-wise linear interpolation. Step components (and the text) take {@code from}'s value until
	 * {@code amount} reaches 1.
	 */
	public static ChannelValues lerp(ChannelValues from, ChannelValues to, double amount) {
		Channel channel = from.channel;
		double[] out = new double[channel.size()];
		for (int i = 0; i < out.length; i++) {
			if (channel.step[i]) {
				out[i] = amount >= 1 ? to.values[i] : from.values[i];
			} else {
				out[i] = from.values[i] + (to.values[i] - from.values[i]) * amount;
			}
		}
		return new ChannelValues(channel, out, amount >= 1 ? to.text : from.text);
	}

	public JsonObject toJson() {
		JsonObject object = new JsonObject();
		for (int i = 0; i < this.values.length; i++) {
			object.addProperty(this.channel.components[i], this.values[i]);
		}
		if (this.text != null) {
			object.addProperty("text", this.text);
		}
		return object;
	}

	/** Reads values by component name; missing components fall back to the channel defaults. */
	public static ChannelValues fromJson(Channel channel, @Nullable JsonElement json) {
		ChannelValues result = new ChannelValues(channel);
		if (json == null || !json.isJsonObject()) {
			return result;
		}
		JsonObject object = json.getAsJsonObject();
		for (int i = 0; i < channel.size(); i++) {
			JsonElement element = object.get(channel.components[i]);
			if (element != null && element.isJsonPrimitive()) {
				result.values[i] = element.getAsDouble();
			}
		}
		if (channel.hasText() && object.has("text")) {
			result.text = object.get("text").getAsString();
		}
		return result;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ChannelValues other && other.channel == this.channel
			&& Arrays.equals(other.values, this.values) && Objects.equals(other.text, this.text);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.channel, Arrays.hashCode(this.values), this.text);
	}
}
