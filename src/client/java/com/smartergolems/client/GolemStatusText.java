package com.smartergolems.client;

import com.smartergolems.golem.GolemStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.jspecify.annotations.Nullable;

/** The "Farming · Harvesting" text shown above a working golem the player is looking at. */
public final class GolemStatusText {
	private GolemStatusText() {}

	/** Show the status only while the player's crosshair is on this working golem. */
	public static boolean shouldShow(Entity entity) {
		return entity instanceof CopperGolem
			&& entity.hasAttached(GolemStatus.ATTACHMENT)
			&& Minecraft.getInstance().crosshairPickEntity == entity;
	}

	/** The status line, prefixed with the golem's name if it has one; null if it isn't working. */
	public static @Nullable Component of(Entity entity) {
		if (!shouldShow(entity)) {
			return null;
		}
		GolemStatus status = entity.getAttached(GolemStatus.ATTACHMENT);
		Component text = Component.translatable("status.smartergolems.format",
			status.task().displayName(), status.activity().displayName());
		return entity.hasCustomName() ? entity.getCustomName().copy().append(" · ").append(text) : text;
	}
}
