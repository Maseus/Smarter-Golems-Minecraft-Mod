package com.smartergolems.golem;

import com.smartergolems.network.OpenTaskScreenPayload;
import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

	/** Called on both client and server; only the server acts, by telling the client to open the task screen. */
	public static InteractionResult onTaskInteraction(CopperGolem golem, Player player) {
		if (player instanceof ServerPlayer serverPlayer) {
			if (ServerPlayNetworking.canSend(serverPlayer, OpenTaskScreenPayload.TYPE)) {
				Optional<GolemTask> current = GolemTasks.get(golem);
				ServerPlayNetworking.send(serverPlayer, new OpenTaskScreenPayload(
					golem.getId(), current.map(GolemTask::type), current.map(GolemTask::anchor), GolemTask.horizontalRadius()));
			} else {
				// Client doesn't have the mod installed, so it can't show the screen.
				player.sendOverlayMessage(Component.translatable("message.smartergolems.client_missing_mod"));
			}
		}
		return InteractionResult.SUCCESS;
	}

	/** Server side: apply the player's choice from the task screen. Empty means "No task". */
	public static void applyTask(CopperGolem golem, ServerPlayer player, Optional<GolemTaskType> type) {
		ServerLevel level = player.level();
		// Stop whatever the golem was doing (vanilla chest sorting or a previous task) before switching.
		((WorkingGolem) golem).smartergolems$resetWorker(level);
		golem.getBrain().stopAll(level, golem);

		if (type.isEmpty()) {
			GolemTasks.clear(golem);
			// Hand back anything the golem was carrying for its old task.
			GolemInventory.dropAll(golem, level);
			player.sendOverlayMessage(Component.translatable("message.smartergolems.task_cleared"));
			return;
		}
		// The work area is centered where the golem stands now; picking a task again re-centers it.
		GolemTask task = new GolemTask(type.get(), golem.blockPosition());
		GolemTasks.set(golem, task);
		BlockPos a = task.anchor();
		int size = GolemTask.areaSize();
		player.sendOverlayMessage(Component.translatable(
			"message.smartergolems.task_set", task.type().displayName(), size, size, a.getX(), a.getY(), a.getZ()));
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, golem.getX(), golem.getY() + 0.8, golem.getZ(), 10, 0.3, 0.3, 0.3, 0.0);
	}
}
