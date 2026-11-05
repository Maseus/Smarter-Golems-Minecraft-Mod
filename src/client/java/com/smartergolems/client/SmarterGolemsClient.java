package com.smartergolems.client;

import com.smartergolems.client.screen.GolemTaskScreen;
import com.smartergolems.network.OpenTaskScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class SmarterGolemsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(OpenTaskScreenPayload.TYPE, (payload, context) ->
			context.client().gui.setScreen(new GolemTaskScreen(payload)));
	}
}
