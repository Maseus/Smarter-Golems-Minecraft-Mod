package com.smartergolems.work;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** What a working golem is doing right now; shown above its head. */
public enum Activity {
	IDLE, HARVESTING, PLANTING, TILLING, FERTILIZING, CHOPPING, COLLECTING, DEPOSITING, RETURNING, MINING, PLACING, USING;

	public static final StreamCodec<ByteBuf, Activity> STREAM_CODEC = ByteBufCodecs.idMapper(
		id -> id >= 0 && id < values().length ? values()[id] : IDLE, Activity::ordinal);

	public Component displayName() {
		return Component.translatable("activity.smartergolems." + name().toLowerCase(java.util.Locale.ROOT));
	}
}
