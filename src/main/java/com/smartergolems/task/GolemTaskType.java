package com.smartergolems.task;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/** The kinds of work a copper golem can be assigned. */
public enum GolemTaskType implements StringRepresentable {
	FARMING("farming");

	public static final Codec<GolemTaskType> CODEC = StringRepresentable.fromEnum(GolemTaskType::values);
	public static final StreamCodec<ByteBuf, GolemTaskType> STREAM_CODEC = ByteBufCodecs.idMapper(GolemTaskType::byId, GolemTaskType::ordinal);

	private final String name;

	GolemTaskType(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	private static GolemTaskType byId(int id) {
		GolemTaskType[] values = values();
		return id >= 0 && id < values.length ? values[id] : values[0];
	}

	public Component displayName() {
		return Component.translatable("task.smartergolems." + name);
	}
}
