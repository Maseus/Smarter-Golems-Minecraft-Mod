package com.smartergolems.network;

import com.smartergolems.SmarterGolems;
import com.smartergolems.task.GolemTaskType;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Server → client: open (or refresh) the task screen for a golem.
 *
 * @param currentTask the golem's task, empty if it has none
 * @param anchor      center of the current work area, empty if no task
 * @param radius      work-area radius from the server config, so the client draws the right outline
 * @param bag         what the golem is carrying
 * @param tool        the item in the golem's hand (its tool), possibly empty
 * @param ownerName   who assigned the current task ("" if nobody)
 * @param canEdit     whether this player may change the task or take items
 */
public record OpenTaskScreenPayload(int golemId, Optional<GolemTaskType> currentTask, Optional<BlockPos> anchor, int radius,
                                    List<ItemStack> bag, ItemStack tool, String ownerName, boolean canEdit)
	implements CustomPacketPayload {
	public static final Type<OpenTaskScreenPayload> TYPE = new Type<>(SmarterGolems.id("open_task_screen"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenTaskScreenPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, OpenTaskScreenPayload::golemId,
		ByteBufCodecs.optional(GolemTaskType.STREAM_CODEC), OpenTaskScreenPayload::currentTask,
		ByteBufCodecs.optional(BlockPos.STREAM_CODEC), OpenTaskScreenPayload::anchor,
		ByteBufCodecs.VAR_INT, OpenTaskScreenPayload::radius,
		ItemStack.OPTIONAL_LIST_STREAM_CODEC, OpenTaskScreenPayload::bag,
		ItemStack.OPTIONAL_STREAM_CODEC, OpenTaskScreenPayload::tool,
		ByteBufCodecs.STRING_UTF8, OpenTaskScreenPayload::ownerName,
		ByteBufCodecs.BOOL, OpenTaskScreenPayload::canEdit,
		OpenTaskScreenPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
