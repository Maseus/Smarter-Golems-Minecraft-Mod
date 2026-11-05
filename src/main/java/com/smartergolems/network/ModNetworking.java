package com.smartergolems.network;

import com.smartergolems.golem.GolemInteraction;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;

public final class ModNetworking {
	/** Extra reach allowed past normal interaction range, since the player may step back while the screen is open. */
	public static final double REACH_BUFFER = 4.0;

	private ModNetworking() {}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(OpenTaskScreenPayload.TYPE, OpenTaskScreenPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetTaskPayload.TYPE, SetTaskPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TakeItemsPayload.TYPE, TakeItemsPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SetTaskPayload.TYPE, (payload, context) ->
			withGolem(context.player(), payload.golemId(), (golem, player) -> GolemInteraction.applyTask(golem, player, payload.task())));
		ServerPlayNetworking.registerGlobalReceiver(TakeItemsPayload.TYPE, (payload, context) ->
			withGolem(context.player(), payload.golemId(), GolemInteraction::takeItems));
	}

	/** Never trust the client: re-check the target and reach on the server. */
	private static void withGolem(ServerPlayer player, int golemId, BiConsumer<CopperGolem, ServerPlayer> action) {
		Entity entity = player.level().getEntity(golemId);
		if (entity instanceof CopperGolem golem && player.isWithinEntityInteractionRange(golem, REACH_BUFFER)) {
			action.accept(golem, player);
		}
	}
}
