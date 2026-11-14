package me.zhdg.billboards.mixin;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.moulberry.flashback.FlashbackGson;
import me.zhdg.billboards.keyframe.BillboardKeyframeTypes;
import me.zhdg.billboards.keyframe.ChannelKeyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Type;

/**
 * Flashback registers a Gson adapter per keyframe class, because its undo history serializes keyframes by their
 * runtime class. Register one for {@link ChannelKeyframe} too, otherwise Gson falls back to reflection and writes
 * JSON that can't be read back (which would lose the whole editor state on the next load).
 */
@Mixin(value = FlashbackGson.class, remap = false)
public class FlashbackGsonMixin {

	@Inject(method = "build", at = @At("RETURN"))
	private static void billboards$registerKeyframeAdapter(CallbackInfoReturnable<GsonBuilder> cir) {
		cir.getReturnValue().registerTypeAdapter(ChannelKeyframe.class, new ChannelKeyframeAdapter());
	}

	private static final class ChannelKeyframeAdapter implements JsonSerializer<ChannelKeyframe>, JsonDeserializer<ChannelKeyframe> {
		@Override
		public JsonElement serialize(ChannelKeyframe src, Type type, JsonSerializationContext context) {
			return src.toJson(context);
		}

		@Override
		public ChannelKeyframe deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			JsonObject object = json.getAsJsonObject();
			BillboardKeyframeTypes.Type keyframeType = BillboardKeyframeTypes.byId(object.get("type").getAsString());
			if (keyframeType == null) {
				throw new JsonParseException("Unknown Billboards keyframe type: " + object.get("type"));
			}
			return ChannelKeyframe.fromJson(keyframeType.channel, object, context);
		}
	}
}
