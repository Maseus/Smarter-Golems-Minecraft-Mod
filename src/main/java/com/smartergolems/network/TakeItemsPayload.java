package com.smartergolems.network;

import com.smartergolems.SmarterGolems;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: the player wants everything in the golem's bag. */
public record TakeItemsPayload(int golemId) implements CustomPacketPayload {
	public static final Type<TakeItemsPayload> TYPE = new Type<>(SmarterGolems.id("take_items"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TakeItemsPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, TakeItemsPayload::golemId,
		TakeItemsPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
