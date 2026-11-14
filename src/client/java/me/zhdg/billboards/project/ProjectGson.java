package me.zhdg.billboards.project;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;

import java.lang.reflect.Type;

public final class ProjectGson {

	public static final Gson GSON = new GsonBuilder()
		.registerTypeAdapter(ChannelValues.class, new ChannelValuesAdapter())
		.setPrettyPrinting()
		.create();

	private ProjectGson() {
	}

	/** Stores channel values by component name, so adding components later doesn't break old files. */
	private static final class ChannelValuesAdapter implements JsonSerializer<ChannelValues>, JsonDeserializer<ChannelValues> {
		@Override
		public JsonElement serialize(ChannelValues src, Type type, JsonSerializationContext context) {
			JsonObject object = src.toJson();
			object.addProperty("channel", src.channel.id());
			return object;
		}

		@Override
		public ChannelValues deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			JsonObject object = json.getAsJsonObject();
			Channel channel = Channel.byId(object.get("channel").getAsString());
			return ChannelValues.fromJson(channel, object);
		}
	}
}
