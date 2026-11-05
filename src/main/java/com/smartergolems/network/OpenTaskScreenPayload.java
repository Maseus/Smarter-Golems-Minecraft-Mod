package com.smartergolems.network;

import com.smartergolems.SmarterGolems;
import com.smartergolems.task.GolemTaskType;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: open the task screen for a golem.
 *
 * @param currentTask the golem's task, empty if it has none
 * @param anchor      center of the current work area, empty if no task
 * @param radius      work-area radius from the server config, so the client draws the right outline
 */
public record OpenTaskScreenPayload(int golemId, Optional<GolemTaskType> currentTask, Optional<BlockPos> anchor, int radius)
	implements CustomPacketPayload {
	public static final Type<OpenTaskScreenPayload> TYPE = new Type<>(SmarterGolems.id("open_task_screen"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenTaskScreenPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, OpenTaskScreenPayload::golemId,
		ByteBufCodecs.optional(GolemTaskType.STREAM_CODEC), OpenTaskScreenPayload::currentTask,
		ByteBufCodecs.optional(BlockPos.STREAM_CODEC), OpenTaskScreenPayload::anchor,
		ByteBufCodecs.VAR_INT, OpenTaskScreenPayload::radius,
		OpenTaskScreenPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
