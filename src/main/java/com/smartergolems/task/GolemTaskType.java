package com.smartergolems.task;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/** The kinds of work a copper golem can be assigned. */
public enum GolemTaskType implements StringRepresentable {
	FARMING("farming");

	public static final Codec<GolemTaskType> CODEC = StringRepresentable.fromEnum(GolemTaskType::values);

	private final String name;

	GolemTaskType(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	public Component displayName() {
		return Component.translatable("task.smartergolems." + name);
	}
}
