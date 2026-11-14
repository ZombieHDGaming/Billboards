package me.zhdg.billboards;

import me.zhdg.billboards.keyframe.BillboardKeyframeTypes;
import me.zhdg.billboards.project.ProjectManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class BillboardsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BillboardKeyframeTypes.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> ProjectManager.tick());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ProjectManager.flush());
	}
}
