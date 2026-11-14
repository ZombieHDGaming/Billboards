package me.zhdg.billboards.mixin;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.keyframe.Keyframe;
import me.zhdg.billboards.keyframe.BillboardKeyframeTypes;
import me.zhdg.billboards.keyframe.ChannelKeyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Type;

/**
 * Flashback's keyframe JSON adapter is a hard-coded switch that throws on unknown types, which would make Flashback
 * fail to save or load an editor state containing Billboards keyframes. Handle ours before the switch runs.
 */
@Mixin(value = Keyframe.TypeAdapter.class, remap = false)
public class KeyframeTypeAdapterMixin {

	@Inject(
		method = "deserialize(Lcom/google/gson/JsonElement;Ljava/lang/reflect/Type;Lcom/google/gson/JsonDeserializationContext;)Lcom/moulberry/flashback/keyframe/Keyframe;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void billboards$deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context, CallbackInfoReturnable<Keyframe> cir) {
		if (!json.isJsonObject()) {
			return;
		}
		JsonObject object = json.getAsJsonObject();
		JsonElement type = object.get("type");
		if (type == null || !type.isJsonPrimitive()) {
			return;
		}
		BillboardKeyframeTypes.Type keyframeType = BillboardKeyframeTypes.byId(type.getAsString());
		if (keyframeType != null) {
			cir.setReturnValue(ChannelKeyframe.fromJson(keyframeType.channel, object, context));
		}
	}

	@Inject(
		method = "serialize(Lcom/moulberry/flashback/keyframe/Keyframe;Ljava/lang/reflect/Type;Lcom/google/gson/JsonSerializationContext;)Lcom/google/gson/JsonElement;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void billboards$serialize(Keyframe src, Type typeOfSrc, JsonSerializationContext context, CallbackInfoReturnable<JsonElement> cir) {
		if (src instanceof ChannelKeyframe keyframe) {
			cir.setReturnValue(keyframe.toJson(context));
		}
	}
}
