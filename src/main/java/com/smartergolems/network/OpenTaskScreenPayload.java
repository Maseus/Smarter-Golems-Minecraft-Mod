package com.smartergolems.network;

import com.smartergolems.SmarterGolems;
import com.smartergolems.task.GolemTaskType;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: open the task screen for a golem. Empty task means the golem has none. */
public record OpenTaskScreenPayload(int golemId, Optional<GolemTaskType> currentTask) implements CustomPacketPayload {
	public static final Type<OpenTaskScreenPayload> TYPE = new Type<>(SmarterGolems.id("open_task_screen"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenTaskScreenPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, OpenTaskScreenPayload::golemId,
		ByteBufCodecs.optional(GolemTaskType.STREAM_CODEC), OpenTaskScreenPayload::currentTask,
		OpenTaskScreenPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
