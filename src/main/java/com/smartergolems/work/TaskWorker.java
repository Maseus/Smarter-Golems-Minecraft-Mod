package com.smartergolems.work;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Shared logic for a golem doing a task: walking to jobs, giving up on unreachable spots,
 * depositing carried items into chests and returning home. Runs on the server every tick
 * instead of the golem's vanilla brain. Subclasses only decide what work to look for.
 *
 * Priorities when idle:
 * 1. Deposit into a chest when the bag is getting full.
 * 2. The task's own work ({@link #findWork}).
 * 3. Deposit whatever is left when there's nothing else to do.
 * 4. Walk back to the middle of the work area.
 *
 * State is not saved: after a reload the golem simply picks its next job again.
 */
public abstract class TaskWorker {
	protected static final double BASE_SPEED = 1.0;
	/** Deposit once this many bag slots are used, even if there's still work. */
	private static final int DEPOSIT_THRESHOLD_SLOTS = 6;
	/** Chests up to this many blocks outside the work area are used. */
	private static final int CHEST_SEARCH_EXTRA = 4;
	private static final double CHEST_REACH = 2.0;
	private static final int REPATH_INTERVAL = 20;
	/** Pathfinding failures (while standing on the ground) before a target counts as unreachable. */
	private static final int MAX_PATH_FAILURES = 3;
	/** How many of a kept item (see {@link #amountToKeep}) stay in the bag when depositing. */
	protected static final int KEEP_AMOUNT = 16;
	private static final int GIVE_UP_TICKS = 200;
	protected static final int UNREACHABLE_COOLDOWN = 400;
	private static final int ACTION_COOLDOWN = 10;
	private static final int IDLE_SCAN_COOLDOWN = 40;
	private static final int DEPOSIT_DURATION = 20;
	private static final int DEPOSIT_SOUND_TICK = 9;
	/**
	 * Pathfinding cost of walking through leaves while working (vanilla: impassable). High enough
	 * that open routes are preferred, but the golem will push through leaves when it has to.
	 */
	private static final float LEAVES_PATH_COST = 8.0F;

	/**
	 * One unit of work: walk to {@code target}, run {@code onArrive} (return false to abort,
	 * e.g. if the target changed), then keep working for {@code workTicks} (scaled by
	 * oxidation) calling {@code onWorkTick}, then run {@code onFinish}.
	 */
	protected record Job(BlockPos target, double reach, int workTicks, BooleanSupplier onArrive,
	                     @Nullable IntConsumer onWorkTick, @Nullable Runnable onFinish) {
		/** A job that happens immediately on arrival. */
		public static Job instant(BlockPos target, double reach, Runnable action) {
			return new Job(target, reach, 0, () -> {
				action.run();
				return true;
			}, null, null);
		}
	}

	private enum Phase { IDLE, MOVING, WORKING, RETURNING }

	private Phase phase = Phase.IDLE;
	private @Nullable Job job;
	private int ticksInPhase;
	private int cooldown;
	private int pathFailures;
	private @Nullable BlockPos openChest;
	/** Targets we failed to reach (or that turned out unusable), and the game time until which they're skipped. */
	private final Map<BlockPos, Long> skipped = new HashMap<>();

	/** The next piece of task-specific work, if any. */
	protected abstract Optional<Job> findWork(ServerLevel level, CopperGolem golem, GolemTask task);

	/**
	 * How many of this item to hold back when depositing, e.g. seeds or saplings needed for
	 * replanting. The total kept per item type is capped at this amount.
	 */
	protected int amountToKeep(ItemStack stack) {
		return 0;
	}

	public final void tick(ServerLevel level, CopperGolem golem, GolemTask task) {
		ticksInPhase++;
		golem.setPathfindingMalus(PathType.LEAVES, LEAVES_PATH_COST);
		if ((phase == Phase.MOVING || phase == Phase.RETURNING) && golem.horizontalCollision) {
			clearLeavesInTheWay(level, golem);
		}
		switch (phase) {
			case IDLE -> tickIdle(level, golem, task);
			case MOVING -> tickMoving(level, golem);
			case WORKING -> tickWorking(golem);
			case RETURNING -> tickReturning(golem);
		}
	}

	/** Stops whatever the golem is doing, e.g. when its task changes. */
	public void reset(ServerLevel level, CopperGolem golem) {
		closeChest(level, golem);
		golem.setPathfindingMalus(PathType.LEAVES, PathType.LEAVES.getMalus());
		golem.getNavigation().stop();
		golem.setState(CopperGolemState.IDLE);
		setPhase(Phase.IDLE, null);
		cooldown = 0;
		skipped.clear();
	}

	// ---- Phases ----

	private void tickIdle(ServerLevel level, CopperGolem golem, GolemTask task) {
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		long now = level.getGameTime();
		skipped.values().removeIf(until -> until <= now);

		boolean canDeposit = hasItemsToDeposit(golem);
		Optional<Job> next = Optional.empty();
		if (canDeposit && GolemInventory.usedSlots(golem) >= DEPOSIT_THRESHOLD_SLOTS) {
			next = depositJob(level, golem, task);
		}
		if (next.isEmpty()) {
			next = findWork(level, golem, task);
		}
		if (next.isEmpty() && canDeposit) {
			next = depositJob(level, golem, task);
		}
		if (next.isPresent()) {
			setPhase(Phase.MOVING, next.get());
			return;
		}
		BlockPos home = task.anchor();
		if (!golem.blockPosition().closerThan(home, 2)) {
			setPhase(Phase.RETURNING, null);
			golem.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, speed(golem));
			return;
		}
		cooldown = WorkSpeed.scaleTicks(golem, IDLE_SCAN_COOLDOWN);
	}

	private void tickMoving(ServerLevel level, CopperGolem golem) {
		BlockPos target = job.target();
		Vec3 center = Vec3.atBottomCenterOf(target);
		golem.getLookControl().setLookAt(center.x, center.y + 0.5, center.z);
		if (isWithinReach(golem, target, job.reach())) {
			golem.getNavigation().stop();
			if (!job.onArrive().getAsBoolean()) {
				finishJob(golem, false);
			} else if (job.workTicks() > 0) {
				setPhase(Phase.WORKING, job);
			} else {
				finishJob(golem, true);
			}
			return;
		}
		if (ticksInPhase > GIVE_UP_TICKS) {
			skip(level, target);
			finishJob(golem, false);
			return;
		}
		if (ticksInPhase % REPATH_INTERVAL == 1 || golem.getNavigation().isDone()) {
			Path path = golem.getNavigation().createPath(target, 1);
			if (path == null) {
				// Navigation refuses to plan while airborne (e.g. mid-jump or just spawned):
				// that isn't a sign the target is unreachable, so just retry next tick.
				boolean canPlan = golem.onGround() || golem.isInLiquid();
				if (canPlan && ++pathFailures >= MAX_PATH_FAILURES) {
					skip(level, target);
					finishJob(golem, false);
				}
				return;
			}
			pathFailures = 0;
			golem.getNavigation().moveTo(path, speed(golem));
		}
	}

	private void tickWorking(CopperGolem golem) {
		Vec3 center = Vec3.atCenterOf(job.target());
		golem.getLookControl().setLookAt(center.x, center.y, center.z);
		if (job.onWorkTick() != null) {
			job.onWorkTick().accept(ticksInPhase);
		}
		if (ticksInPhase >= WorkSpeed.scaleTicks(golem, job.workTicks())) {
			finishJob(golem, true);
		}
	}

	private void tickReturning(CopperGolem golem) {
		if (golem.getNavigation().isDone() || ticksInPhase > GIVE_UP_TICKS) {
			golem.getNavigation().stop();
			cooldown = WorkSpeed.scaleTicks(golem, IDLE_SCAN_COOLDOWN);
			setPhase(Phase.IDLE, null);
		}
	}

	private void finishJob(CopperGolem golem, boolean completed) {
		if (completed && job != null && job.onFinish() != null) {
			job.onFinish().run();
		}
		cooldown = WorkSpeed.scaleTicks(golem, ACTION_COOLDOWN);
		setPhase(Phase.IDLE, null);
	}

	private void setPhase(Phase next, @Nullable Job newJob) {
		phase = next;
		job = newJob;
		ticksInPhase = 0;
		pathFailures = 0;
	}

	/**
	 * Breaks natural leaves the golem is walking into, like a ravager does, keeping the drops.
	 * Only beside and above the golem (not below, so it doesn't dig down), and never
	 * player-placed (persistent) leaves.
	 */
	private static void clearLeavesInTheWay(ServerLevel level, CopperGolem golem) {
		AABB box = golem.getBoundingBox().inflate(0.2, 0.0, 0.2);
		for (BlockPos pos : BlockPos.betweenClosed(
			Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
			Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
			if (isNaturalLeaves(level.getBlockState(pos))) {
				collectDrops(level, golem, pos);
				level.destroyBlock(pos, false, golem);
			}
		}
	}

	// ---- Helpers for subclasses ----

	/** Leaves that grew naturally (player-placed leaves are "persistent"). */
	protected static boolean isNaturalLeaves(BlockState state) {
		return state.getBlock() instanceof LeavesBlock
			&& state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
	}

	/** Puts a block's drops into the golem's bag (overflow is dropped on the ground). */
	protected static void collectDrops(ServerLevel level, CopperGolem golem, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		for (ItemStack drop : Block.getDrops(state, level, pos, null, golem, ItemStack.EMPTY)) {
			GolemInventory.addOrDrop(golem, level, drop);
		}
	}

	protected static double speed(CopperGolem golem) {
		return BASE_SPEED * WorkSpeed.multiplier(golem);
	}

	/** Skip this position for a while (unreachable, not usable, chest full...). */
	protected void skip(ServerLevel level, BlockPos pos) {
		skipped.put(pos.immutable(), level.getGameTime() + UNREACHABLE_COOLDOWN);
	}

	protected void skip(ServerLevel level, BlockPos pos, int ticks) {
		skipped.put(pos.immutable(), level.getGameTime() + ticks);
	}

	/** Nearest matching position that isn't currently skipped. */
	protected Optional<BlockPos> findNearest(CopperGolem golem, Iterable<BlockPos> positions, Predicate<BlockPos> matches) {
		BlockPos from = golem.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : positions) {
			if (skipped.containsKey(pos) || !matches.test(pos)) {
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

	private static boolean isWithinReach(CopperGolem golem, BlockPos pos, double reach) {
		Vec3 center = Vec3.atBottomCenterOf(pos);
		double dx = golem.getX() - center.x;
		double dz = golem.getZ() - center.z;
		return dx * dx + dz * dz <= reach * reach && Math.abs(golem.getY() - center.y) <= 2.0;
	}

	// ---- Depositing ----

	private Optional<Job> depositJob(ServerLevel level, CopperGolem golem, GolemTask task) {
		BlockPos anchor = task.anchor();
		int radius = GolemTask.horizontalRadius() + CHEST_SEARCH_EXTRA;
		Iterable<BlockPos> positions = BlockPos.betweenClosed(
			anchor.offset(-radius, -GolemTask.VERTICAL_RADIUS, -radius),
			anchor.offset(radius, GolemTask.VERTICAL_RADIUS, radius));
		return findNearest(golem, positions, pos -> getChest(level, pos) != null).map(chestPos -> new Job(
			chestPos, CHEST_REACH, DEPOSIT_DURATION,
			() -> startDeposit(level, golem, chestPos),
			tick -> {
				if (tick == DEPOSIT_SOUND_TICK) {
					// Same timing as the vanilla golem's item drop-off sound.
					golem.playSound(SoundEvents.COPPER_GOLEM_ITEM_DROP);
				}
			},
			() -> closeChest(level, golem)
		));
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

	private boolean startDeposit(ServerLevel level, CopperGolem golem, BlockPos chestPos) {
		Container chest = getChest(level, chestPos);
		if (chest == null) {
			skip(level, chestPos);
			return false;
		}
		chest.startOpen(golem);
		openChest = chestPos;
		golem.setOpenedChestPos(chestPos);
		golem.setState(CopperGolemState.DROPPING_ITEM);

		boolean chestFull = false;
		SimpleContainer bag = GolemInventory.get(golem);
		Map<Item, Integer> kept = new HashMap<>();
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack stack = bag.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			int keep = Math.min(stack.getCount(), amountToKeep(stack) - kept.getOrDefault(stack.getItem(), 0));
			if (keep > 0) {
				kept.merge(stack.getItem(), keep, Integer::sum);
			}
			ItemStack toDeposit = stack.split(stack.getCount() - Math.max(keep, 0));
			ItemStack remainder = HopperBlockEntity.addItem(null, chest, toDeposit, null);
			stack.grow(remainder.getCount());
			chestFull |= !remainder.isEmpty();
		}
		bag.setChanged();
		if (chestFull) {
			skip(level, chestPos); // try a different chest for a while
		}
		return true;
	}

	/** Whether the bag holds anything beyond the items this task keeps for itself. */
	private boolean hasItemsToDeposit(CopperGolem golem) {
		if (GolemInventory.isEmpty(golem)) {
			return false;
		}
		Map<Item, Integer> totals = new HashMap<>();
		Map<Item, ItemStack> samples = new HashMap<>();
		for (ItemStack stack : GolemInventory.get(golem).getItems()) {
			if (!stack.isEmpty()) {
				totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
				samples.putIfAbsent(stack.getItem(), stack);
			}
		}
		for (Map.Entry<Item, Integer> entry : totals.entrySet()) {
			if (entry.getValue() > amountToKeep(samples.get(entry.getKey()))) {
				return true;
			}
		}
		return false;
	}

	private void closeChest(ServerLevel level, CopperGolem golem) {
		if (openChest != null) {
			Container chest = getChest(level, openChest);
			if (chest != null && chest.getEntitiesWithContainerOpen().contains(golem)) {
				chest.stopOpen(golem);
			}
			openChest = null;
			golem.clearOpenedChestPos();
			golem.setState(CopperGolemState.IDLE);
		}
	}
}
