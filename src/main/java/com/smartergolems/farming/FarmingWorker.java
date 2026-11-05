package com.smartergolems.farming;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Drives a copper golem with the farming task. Runs on the server every tick instead of
 * the golem's vanilla brain. Priorities, checked when idle:
 * 1. Deposit carried items into a chest when the bag is getting full (or there's nothing else to do).
 * 2. Harvest the nearest fully grown crop and replant it.
 * 3. Plant carried seeds on empty farmland.
 * 4. Walk back to the middle of the farm.
 * State is not saved: after a reload the golem simply picks its next job again.
 */
public class FarmingWorker {
	private static final double SPEED = 1.0;
	/** Deposit once this many bag slots are used, even if there's still work. */
	private static final int DEPOSIT_THRESHOLD_SLOTS = 6;
	/** Chests up to this many blocks outside the farm area are used. */
	private static final int CHEST_SEARCH_EXTRA = 4;
	private static final double CROP_REACH = 1.5;
	private static final double CHEST_REACH = 2.0;
	private static final int REPATH_INTERVAL = 20;
	private static final int GIVE_UP_TICKS = 200;
	private static final int UNREACHABLE_COOLDOWN = 400;
	private static final int ACTION_COOLDOWN = 10;
	private static final int IDLE_SCAN_COOLDOWN = 40;
	private static final int DEPOSIT_DURATION = 20;

	private enum State { IDLE, HARVEST, PLANT, DEPOSIT, RETURN, DEPOSITING }

	private State state = State.IDLE;
	private @Nullable BlockPos target;
	private int ticksInState;
	private int cooldown;
	/** Targets we failed to reach, and the game time until which they're skipped. */
	private final Map<BlockPos, Long> unreachable = new HashMap<>();

	public void tick(ServerLevel level, CopperGolem golem, GolemTask task) {
		ticksInState++;
		switch (state) {
			case IDLE -> tickIdle(level, golem, task);
			case HARVEST -> tickMoveThenAct(level, golem, CROP_REACH, () -> harvest(level, golem));
			case PLANT -> tickMoveThenAct(level, golem, CROP_REACH, () -> plant(level, golem));
			case DEPOSIT -> tickMoveThenAct(level, golem, CHEST_REACH, () -> startDeposit(level, golem));
			case RETURN -> tickReturn(golem, task);
			case DEPOSITING -> tickDepositing(level, golem);
		}
	}

	/** Stops whatever the golem is doing, e.g. when its task changes. */
	public void reset(ServerLevel level, CopperGolem golem) {
		if (state == State.DEPOSITING) {
			closeChest(level, golem);
		}
		golem.getNavigation().stop();
		golem.setState(CopperGolemState.IDLE);
		setState(State.IDLE, null);
		cooldown = 0;
		unreachable.clear();
	}

	// ---- Deciding what to do ----

	private void tickIdle(ServerLevel level, CopperGolem golem, GolemTask task) {
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		long now = level.getGameTime();
		unreachable.values().removeIf(until -> until <= now);

		int usedSlots = GolemInventory.usedSlots(golem);
		if (usedSlots >= DEPOSIT_THRESHOLD_SLOTS && tryStart(State.DEPOSIT, findChest(level, golem, task))) {
			return;
		}
		if (tryStart(State.HARVEST, findRipeCrop(level, golem, task))) {
			return;
		}
		if (tryStart(State.PLANT, findPlantableFarmland(level, golem, task))) {
			return;
		}
		if (usedSlots > 0 && tryStart(State.DEPOSIT, findChest(level, golem, task))) {
			return;
		}
		if (!golem.blockPosition().closerThan(task.anchor(), 2)) {
			setState(State.RETURN, task.anchor());
			golem.getNavigation().moveTo(task.anchor().getX() + 0.5, task.anchor().getY(), task.anchor().getZ() + 0.5, SPEED);
			return;
		}
		cooldown = IDLE_SCAN_COOLDOWN;
	}

	private boolean tryStart(State next, Optional<BlockPos> pos) {
		if (pos.isEmpty()) {
			return false;
		}
		setState(next, pos.get());
		return true;
	}

	private void setState(State next, @Nullable BlockPos newTarget) {
		state = next;
		target = newTarget;
		ticksInState = 0;
	}

	// ---- Walking ----

	private void tickMoveThenAct(ServerLevel level, CopperGolem golem, double reach, Runnable action) {
		if (target == null) {
			setState(State.IDLE, null);
			return;
		}
		Vec3 center = Vec3.atBottomCenterOf(target);
		golem.getLookControl().setLookAt(center.x, center.y + 0.5, center.z);
		if (isWithinReach(golem, target, reach)) {
			golem.getNavigation().stop();
			action.run();
			return;
		}
		if (ticksInState > GIVE_UP_TICKS) {
			giveUp(level);
			return;
		}
		if (ticksInState % REPATH_INTERVAL == 1 || golem.getNavigation().isDone()) {
			Path path = golem.getNavigation().createPath(target, 1);
			if (path == null) {
				giveUp(level);
				return;
			}
			golem.getNavigation().moveTo(path, SPEED);
		}
	}

	private static boolean isWithinReach(CopperGolem golem, BlockPos pos, double reach) {
		Vec3 center = Vec3.atBottomCenterOf(pos);
		double dx = golem.getX() - center.x;
		double dz = golem.getZ() - center.z;
		return dx * dx + dz * dz <= reach * reach && Math.abs(golem.getY() - center.y) <= 2.0;
	}

	private void giveUp(ServerLevel level) {
		if (target != null) {
			unreachable.put(target.immutable(), level.getGameTime() + UNREACHABLE_COOLDOWN);
		}
		cooldown = ACTION_COOLDOWN;
		setState(State.IDLE, null);
	}

	private void tickReturn(CopperGolem golem, GolemTask task) {
		if (golem.getNavigation().isDone() || ticksInState > GIVE_UP_TICKS) {
			golem.getNavigation().stop();
			cooldown = IDLE_SCAN_COOLDOWN;
			setState(State.IDLE, null);
		}
	}

	// ---- Harvesting and planting ----

	private Optional<BlockPos> findRipeCrop(ServerLevel level, CopperGolem golem, GolemTask task) {
		return findNearest(golem, task.areaPositions(), pos -> {
			BlockState blockState = level.getBlockState(pos);
			return blockState.getBlock() instanceof CropBlock crop && crop.isMaxAge(blockState);
		});
	}

	private Optional<BlockPos> findPlantableFarmland(ServerLevel level, CopperGolem golem, GolemTask task) {
		if (findSeed(golem).isEmpty()) {
			return Optional.empty();
		}
		return findNearest(golem, task.areaPositions(), pos ->
			level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(Blocks.FARMLAND));
	}

	private void harvest(ServerLevel level, CopperGolem golem) {
		BlockPos pos = target;
		BlockState blockState = level.getBlockState(pos);
		if (blockState.getBlock() instanceof CropBlock crop && crop.isMaxAge(blockState)) {
			List<ItemStack> drops = Block.getDrops(blockState, level, pos, null, golem, ItemStack.EMPTY);
			level.destroyBlock(pos, false, golem);

			// Replant using a seed from the harvest itself, falling back to the bag.
			Item seed = crop.asItem();
			boolean replanted = false;
			for (ItemStack drop : drops) {
				if (!replanted && drop.is(seed)) {
					drop.shrink(1);
					replanted = placeCrop(level, golem, pos, crop);
				}
			}
			for (ItemStack drop : drops) {
				if (!drop.isEmpty()) {
					GolemInventory.addOrDrop(golem, level, drop);
				}
			}
			if (!replanted) {
				takeFromBag(golem, seed).ifPresent(stack -> placeCrop(level, golem, pos, crop));
			}
		}
		cooldown = ACTION_COOLDOWN;
		setState(State.IDLE, null);
	}

	private void plant(ServerLevel level, CopperGolem golem) {
		BlockPos pos = target;
		if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
			findSeed(golem).ifPresent(crop -> {
				if (crop.defaultBlockState().canSurvive(level, pos)) {
					takeFromBag(golem, crop.asItem()).ifPresent(stack -> placeCrop(level, golem, pos, crop));
				}
			});
		}
		cooldown = ACTION_COOLDOWN;
		setState(State.IDLE, null);
	}

	private static boolean placeCrop(ServerLevel level, CopperGolem golem, BlockPos pos, CropBlock crop) {
		BlockState planted = crop.getStateForAge(0);
		if (!planted.canSurvive(level, pos)) {
			return false;
		}
		level.setBlockAndUpdate(pos, planted);
		level.gameEvent(golem, GameEvent.BLOCK_PLACE, pos);
		return true;
	}

	/** The crop block of the first plantable seed in the golem's bag. */
	private static Optional<CropBlock> findSeed(CopperGolem golem) {
		if (GolemInventory.isEmpty(golem)) {
			return Optional.empty();
		}
		for (ItemStack stack : GolemInventory.get(golem).getItems()) {
			if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof CropBlock crop) {
				return Optional.of(crop);
			}
		}
		return Optional.empty();
	}

	/** Removes one of the given item from the bag. */
	private static Optional<ItemStack> takeFromBag(CopperGolem golem, Item item) {
		SimpleContainer bag = GolemInventory.get(golem);
		for (int i = 0; i < bag.getContainerSize(); i++) {
			if (bag.getItem(i).is(item)) {
				return Optional.of(bag.removeItem(i, 1));
			}
		}
		return Optional.empty();
	}

	// ---- Depositing ----

	private Optional<BlockPos> findChest(ServerLevel level, CopperGolem golem, GolemTask task) {
		BlockPos anchor = task.anchor();
		int radius = GolemTask.HORIZONTAL_RADIUS + CHEST_SEARCH_EXTRA;
		Iterable<BlockPos> positions = BlockPos.betweenClosed(
			anchor.offset(-radius, -GolemTask.VERTICAL_RADIUS, -radius),
			anchor.offset(radius, GolemTask.VERTICAL_RADIUS, radius));
		return findNearest(golem, positions, pos -> getChest(level, pos) != null);
	}

	/** The chest's container (both halves for a double chest), or null if it isn't a usable chest. */
	private static @Nullable Container getChest(ServerLevel level, BlockPos pos) {
		BlockState blockState = level.getBlockState(pos);
		boolean isChest = blockState.is(Blocks.CHEST) || blockState.is(Blocks.TRAPPED_CHEST) || blockState.is(BlockTags.COPPER_CHESTS);
		if (isChest && blockState.getBlock() instanceof ChestBlock chestBlock) {
			return ChestBlock.getContainer(chestBlock, blockState, level, pos, false);
		}
		return null;
	}

	private void startDeposit(ServerLevel level, CopperGolem golem) {
		Container chest = getChest(level, target);
		if (chest == null) {
			giveUp(level);
			return;
		}
		chest.startOpen(golem);
		golem.setOpenedChestPos(target);
		golem.setState(CopperGolemState.DROPPING_ITEM);

		boolean chestFull = false;
		SimpleContainer bag = GolemInventory.get(golem);
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack stack = bag.getItem(i);
			if (!stack.isEmpty()) {
				ItemStack remainder = HopperBlockEntity.addItem(null, chest, stack, null);
				bag.setItem(i, remainder);
				chestFull |= !remainder.isEmpty();
			}
		}
		if (chestFull) {
			// Try a different chest for a while.
			unreachable.put(target.immutable(), level.getGameTime() + UNREACHABLE_COOLDOWN);
		}
		BlockPos chestPos = target;
		setState(State.DEPOSITING, chestPos);
	}

	private void tickDepositing(ServerLevel level, CopperGolem golem) {
		if (ticksInState >= DEPOSIT_DURATION) {
			closeChest(level, golem);
			cooldown = ACTION_COOLDOWN;
			setState(State.IDLE, null);
		}
	}

	private void closeChest(ServerLevel level, CopperGolem golem) {
		if (target != null) {
			Container chest = getChest(level, target);
			if (chest != null && chest.getEntitiesWithContainerOpen().contains(golem)) {
				chest.stopOpen(golem);
			}
		}
		golem.clearOpenedChestPos();
		golem.setState(CopperGolemState.IDLE);
	}

	// ---- Helpers ----

	private Optional<BlockPos> findNearest(CopperGolem golem, Iterable<BlockPos> positions, java.util.function.Predicate<BlockPos> matches) {
		BlockPos from = golem.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : positions) {
			if (unreachable.containsKey(pos) || !matches.test(pos)) {
				continue;
			}
			double dist = pos.distSqr(from);
			if (dist < bestDist) {
				bestDist = dist;
				best = pos.immutable();
			}
		}
		return Optional.ofNullable(best);
	}
}
