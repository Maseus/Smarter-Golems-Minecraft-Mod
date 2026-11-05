package com.smartergolems.golem;

import com.smartergolems.network.OpenTaskScreenPayload;
import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Handles the "sneak + right-click" interaction and the task screen's requests. */
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
				openScreen(golem, serverPlayer);
			} else {
				// Client doesn't have the mod installed, so it can't show the screen.
				player.sendOverlayMessage(Component.translatable("message.smartergolems.client_missing_mod"));
			}
		}
		return InteractionResult.SUCCESS;
	}

	/** Sends the task screen (or a refreshed copy of it) to the player. */
	public static void openScreen(CopperGolem golem, ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, OpenTaskScreenPayload.TYPE)) {
			return; // client without the mod (or a test player): nothing to show
		}
		Optional<GolemTask> current = GolemTasks.get(golem);
		List<ItemStack> bag = new ArrayList<>();
		if (golem.hasAttached(GolemInventory.CARRIED)) {
			for (ItemStack stack : GolemInventory.get(golem).getItems()) {
				bag.add(stack.copy());
			}
		}
		ServerPlayNetworking.send(player, new OpenTaskScreenPayload(
			golem.getId(), current.map(GolemTask::type), current.map(GolemTask::anchor), GolemTask.horizontalRadius(),
			bag, golem.getMainHandItem().copy(), current.map(GolemTask::ownerName).orElse(""), canEdit(golem, player)));
	}

	/**
	 * Owner lock: only the player who assigned the current task (or an operator) may change it
	 * or take items. A golem without a task, or with an unowned task, is free for anyone.
	 */
	public static boolean canEdit(CopperGolem golem, ServerPlayer player) {
		Optional<GolemTask> task = GolemTasks.get(golem);
		return task.isEmpty()
			|| task.get().owner().isEmpty()
			|| task.get().owner().get().equals(player.getUUID())
			|| player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static boolean checkCanEdit(CopperGolem golem, ServerPlayer player) {
		if (canEdit(golem, player)) {
			return true;
		}
		String owner = GolemTasks.get(golem).map(GolemTask::ownerName).orElse("?");
		player.sendOverlayMessage(Component.translatable("message.smartergolems.not_owner", owner));
		return false;
	}

	/** Server side: apply the player's choice from the task screen. Empty means "No task". */
	public static void applyTask(CopperGolem golem, ServerPlayer player, Optional<GolemTaskType> type) {
		if (!checkCanEdit(golem, player)) {
			return;
		}
		ServerLevel level = player.level();
		// Stop whatever the golem was doing (vanilla chest sorting or a previous task) before switching.
		((WorkingGolem) golem).smartergolems$resetWorker(level);
		golem.getBrain().stopAll(level, golem);

		if (type.isEmpty()) {
			GolemTasks.clear(golem);
			GolemStatus.clear(golem);
			// Hand back anything the golem was carrying for its old task. Its tool stays in its
			// hand: take it with a normal right-click, like any item a copper golem holds.
			GolemInventory.dropAll(golem, level);
			player.sendOverlayMessage(Component.translatable("message.smartergolems.task_cleared"));
			return;
		}
		// Something in its hand the new task can't use (vanilla cargo, or another task's tool) goes in the bag.
		ItemStack held = golem.getMainHandItem();
		if (!held.isEmpty() && !((WorkingGolem) golem).smartergolems$workerFor(type.get()).wantsTool(held)) {
			golem.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			GolemInventory.addOrDrop(golem, level, held);
		}
		// The work area is centered where the golem stands now; picking a task again re-centers it.
		GolemTask task = new GolemTask(type.get(), golem.blockPosition(), Optional.of(player.getUUID()), player.getPlainTextName());
		GolemTasks.set(golem, task);
		BlockPos a = task.anchor();
		int size = GolemTask.areaSize();
		player.sendOverlayMessage(Component.translatable(
			"message.smartergolems.task_set", task.type().displayName(), size, size, a.getX(), a.getY(), a.getZ()));
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, golem.getX(), golem.getY() + 0.8, golem.getZ(), 10, 0.3, 0.3, 0.3, 0.0);
	}

	/** Server side: give the player everything in the golem's bag, then refresh their screen. */
	public static void takeItems(CopperGolem golem, ServerPlayer player) {
		if (!checkCanEdit(golem, player)) {
			return;
		}
		if (golem.hasAttached(GolemInventory.CARRIED)) {
			SimpleContainer bag = GolemInventory.get(golem);
			for (ItemStack stack : bag.removeAllItems()) {
				if (!stack.isEmpty()) {
					player.getInventory().add(stack);
					if (!stack.isEmpty()) {
						player.spawnAtLocation(player.level(), stack); // inventory full
					}
				}
			}
		}
		openScreen(golem, player);
	}
}
