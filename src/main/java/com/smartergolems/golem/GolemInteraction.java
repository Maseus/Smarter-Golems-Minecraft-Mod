package com.smartergolems.golem;

import com.smartergolems.network.OpenTaskScreenPayload;
import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import com.smartergolems.task.TaughtBlock;
import com.smartergolems.task.TaughtUse;
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
import com.smartergolems.work.TaskWorker;

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

	/**
	 * While teaching, sneak + right-clicking the golem being taught finishes teaching, whatever
	 * the player holds (they are probably still holding a pickaxe). Server side only.
	 */
	public static boolean tryFinishTeaching(CopperGolem golem, Player player, InteractionHand hand) {
		if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown()
			&& player instanceof ServerPlayer serverPlayer && Teaching.isTeaching(serverPlayer, golem)) {
			Teaching.finish(golem, serverPlayer);
			return true;
		}
		return false;
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
			golem.getId(), current.map(GolemTask::type), current.map(GolemTask::anchor),
			current.map(GolemTask::radius).orElse(GolemTask.radiusFor(GolemTaskType.WOODCUTTING)),
			bag, golem.getMainHandItem().copy(), current.map(GolemTask::ownerName).orElse(""), canEdit(golem, player),
			current.map(GolemTask::taughtPositions).orElse(List.of())));
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

	/**
	 * Server side: apply the player's choice from the task screen. Empty means "No task". Custom
	 * doesn't change the task yet: it starts teaching, and the task is set when teaching finishes.
	 */
	public static void applyTask(CopperGolem golem, ServerPlayer player, Optional<GolemTaskType> type) {
		if (!checkCanEdit(golem, player)) {
			return;
		}
		if (type.isPresent() && type.get() == GolemTaskType.CUSTOM) {
			Teaching.start(golem, player);
			return;
		}
		ServerLevel level = player.level();
		stopCurrentWork(golem, level);

		if (type.isEmpty()) {
			GolemTasks.clear(golem);
			GolemStatus.clear(golem);
			// Hand back anything the golem was carrying for its old task. Its tool stays in its
			// hand: take it with a normal right-click, like any item a copper golem holds.
			GolemInventory.dropAll(golem, level);
			player.sendOverlayMessage(Component.translatable("message.smartergolems.task_cleared"));
			return;
		}
		stashUnusableHeldItem(golem, level, type.get());
		// Farming centers on the nearest chest (e.g. a waterlogged chest in the middle of a 9x9 farm);
		// otherwise the area is centered where the golem stands. Picking a task again re-centers it.
		Optional<BlockPos> chest = type.get() == GolemTaskType.FARMING ? findFarmChest(level, golem) : Optional.empty();
		GolemTask task = new GolemTask(type.get(), chest.orElse(golem.blockPosition()), Optional.of(player.getUUID()), player.getPlainTextName());
		GolemTasks.set(golem, task);
		BlockPos a = task.anchor();
		int size = task.areaSize();
		player.sendOverlayMessage(Component.translatable(
			chest.isPresent() ? "message.smartergolems.task_set_chest" : "message.smartergolems.task_set",
			task.type().displayName(), size, size, a.getX(), a.getY(), a.getZ()));
		sparkle(golem, level);
	}

	/** Server side: teaching finished; give the golem a custom task doing what it was shown. */
	public static void applyCustomTask(CopperGolem golem, ServerPlayer player, BlockPos anchor, List<TaughtBlock> taught, List<TaughtUse> uses) {
		if (!checkCanEdit(golem, player)) {
			return;
		}
		ServerLevel level = player.level();
		stopCurrentWork(golem, level);
		GolemTask task = new GolemTask(GolemTaskType.CUSTOM, anchor, Optional.of(player.getUUID()), player.getPlainTextName(), taught, uses);
		GolemTasks.set(golem, task);
		// Tell the worker what was taught, so it judges the held tool by the new taught blocks.
		((WorkingGolem) golem).smartergolems$workerFor(GolemTaskType.CUSTOM).prepare(task);
		stashUnusableHeldItem(golem, level, GolemTaskType.CUSTOM);
		int size = task.areaSize();
		player.sendOverlayMessage(Component.translatable("message.smartergolems.custom_set", taught.size() + uses.size(), size, size));
		sparkle(golem, level);
	}

	/** Stops whatever the golem was doing (vanilla chest sorting or a previous task) before switching. */
	private static void stopCurrentWork(CopperGolem golem, ServerLevel level) {
		((WorkingGolem) golem).smartergolems$resetWorker(level);
		golem.getBrain().stopAll(level, golem);
	}

	/** Something in its hand the new task can't use (vanilla cargo, or another task's tool) goes in the bag. */
	private static void stashUnusableHeldItem(CopperGolem golem, ServerLevel level, GolemTaskType type) {
		ItemStack held = golem.getMainHandItem();
		if (!held.isEmpty() && !((WorkingGolem) golem).smartergolems$workerFor(type).wantsTool(held)) {
			golem.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			GolemInventory.addOrDrop(golem, level, held);
		}
	}

	private static void sparkle(CopperGolem golem, ServerLevel level) {
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, golem.getX(), golem.getY() + 0.8, golem.getZ(), 10, 0.3, 0.3, 0.3, 0.0);
	}

	/** The chest nearest the golem, within one farm's width of it, to center a farm on. */
	public static Optional<BlockPos> findFarmChest(ServerLevel level, CopperGolem golem) {
		int reach = GolemTask.radiusFor(GolemTaskType.FARMING) * 2;
		BlockPos from = golem.blockPosition();
		BlockPos best = null;
		for (BlockPos pos : BlockPos.betweenClosed(from.offset(-reach, -GolemTask.VERTICAL_RADIUS, -reach),
			from.offset(reach, GolemTask.VERTICAL_RADIUS, reach))) {
			if (TaskWorker.isUsableChest(level.getBlockState(pos)) && (best == null || pos.distSqr(from) < best.distSqr(from))) {
				best = pos.immutable();
			}
		}
		return Optional.ofNullable(best);
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
