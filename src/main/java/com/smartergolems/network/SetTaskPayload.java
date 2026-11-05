package com.smartergolems.network;

import com.smartergolems.SmarterGolems;
import com.smartergolems.task.GolemTaskType;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: the player picked a task for a golem. Empty task means "No task". */
public record SetTaskPayload(int golemId, Optional<GolemTaskType> task) implements CustomPacketPayload {
	public static final Type<SetTaskPayload> TYPE = new Type<>(SmarterGolems.id("set_task"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SetTaskPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, SetTaskPayload::golemId,
		ByteBufCodecs.optional(GolemTaskType.STREAM_CODEC), SetTaskPayload::task,
		SetTaskPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
