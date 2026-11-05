package com.smartergolems.golem;

import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;

/** Handles the "sneak + right-click" interaction that assigns tasks to a copper golem. */
public final class GolemInteraction {
	private GolemInteraction() {}

	/**
	 * Sneaking with an empty main hand. The empty-hand requirement keeps vanilla
	 * sneak interactions (shears, honeycomb waxing, axe scraping) working.
	 */
	public static boolean isTaskInteraction(Player player, InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND
			&& player.isShiftKeyDown()
			&& player.getItemInHand(hand).isEmpty();
	}

	/** Called on both client and server; only the server acts. */
	public static InteractionResult onTaskInteraction(CopperGolem golem, Player player) {
		if (!golem.level().isClientSide()) {
			// Temporary until the task GUI exists (plan step 4): toggle farming on/off.
			Optional<GolemTask> current = GolemTasks.get(golem);
			if (current.isPresent()) {
				GolemTasks.clear(golem);
				player.sendOverlayMessage(Component.translatable("message.smartergolems.task_cleared"));
			} else {
				GolemTask task = new GolemTask(GolemTaskType.FARMING, golem.blockPosition());
				GolemTasks.set(golem, task);
				BlockPos a = task.anchor();
				player.sendOverlayMessage(Component.translatable(
					"message.smartergolems.task_set", task.type().displayName(), a.getX(), a.getY(), a.getZ()));
			}
		}
		return InteractionResult.SUCCESS;
	}
}
