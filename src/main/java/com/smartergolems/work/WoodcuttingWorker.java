package com.smartergolems.work;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jspecify.annotations.Nullable;

/**
 * Woodcutting: walk to the nearest tree in the area, chop for a while, then fell the whole
 * tree (logs and the natural leaves that would decay without it) into the bag and replant a
 * sapling on the stump. Neighboring trees keep their canopies.
 *
 * <p>To leave player builds alone, something only counts as a tree when: it has natural
 * (non-player-placed) leaves attached, it has no stripped logs, and its base is at most a 2x2
 * trunk standing on dirt/grass. A free-standing log wall would have a much wider base.
 * Limitation: logs touching a tree (even diagonally) count as part of it, so builds that
 * touch trees inside the work area can be cut.
 */
public class WoodcuttingWorker extends TaskWorker {
	private static final double TREE_REACH = 2.0;
	private static final int CHOP_TICKS = 60;
	private static final int MIN_CHOP_TICKS = 8;
	private static final double SAPLING_REACH = 1.5;
	private static final int CHOP_HIT_INTERVAL = 10;
	/** How far (horizontally) from its base a tree's logs and leaves may spread. */
	private static final int MAX_TREE_SPREAD = 8;
	private static final int MAX_LOGS = 200;
	private static final int MAX_LEAVES = 1000;
	private static final int MIN_LEAVES = 4;
	/** Leaves this many blocks or fewer (through leaves) from a log don't decay (vanilla: 6). */
	private static final int LEAF_SUPPORT_DISTANCE = 6;
	/** Largest trunk base: 2x2 (dark oak, big spruce, big jungle). */
	private static final int MAX_STUMPS = 4;
	/** Things that look like trees but aren't (e.g. a log pillar) are skipped for a long while. */
	private static final int NOT_A_TREE_COOLDOWN = 2400;

	/** The logs and leaves of one tree, and the ground-level logs to replant on. */
	record Tree(List<BlockPos> logs, List<BlockPos> leaves, List<BlockPos> stumps) {}

	@Override
	protected Optional<Job> findWork(ServerLevel level, CopperGolem golem, GolemTask task) {
		// One scan of the area, then check candidates nearest first; anything that turns out
		// not to be a tree (e.g. a log pillar) is skipped for a while.
		List<BlockPos> candidates = new ArrayList<>();
		for (BlockPos pos : task.areaPositions()) {
			if (!isSkipped(pos) && isTrunkBase(level, pos)) {
				candidates.add(pos.immutable());
			}
		}
		BlockPos from = golem.blockPosition();
		candidates.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(from)));
		for (BlockPos base : candidates) {
			if (scanTree(level, base) != null) {
				return Optional.of(chopJob(level, golem, base));
			}
			skip(level, base, NOT_A_TREE_COOLDOWN);
		}
		return fertilizeJob(level, golem, task);
	}

	/** With bone meal in the bag: help saplings in the area grow. */
	private Optional<Job> fertilizeJob(ServerLevel level, CopperGolem golem, GolemTask task) {
		if (boneMealSlot(golem) < 0) {
			return Optional.empty();
		}
		return findNearest(golem, task.areaPositions(), pos -> level.getBlockState(pos).getBlock() instanceof SaplingBlock)
			.map(pos -> Job.instant(Activity.FERTILIZING, pos, SAPLING_REACH, () -> {
				int slot = boneMealSlot(golem);
				if (slot >= 0 && level.getBlockState(pos).getBlock() instanceof SaplingBlock
					&& BoneMealItem.growCrop(GolemInventory.get(golem).getItem(slot), level, pos)) {
					GolemInventory.get(golem).setChanged();
					level.levelEvent(1505, pos, 15); // bone meal particles
				}
			}));
	}

	/** Keep some saplings for replanting, in case a felled tree's leaves drop none. */
	@Override
	protected int amountToKeep(ItemStack stack) {
		return stack.is(ItemTags.SAPLINGS) ? KEEP_AMOUNT : super.amountToKeep(stack);
	}

	@Override
	public boolean wantsTool(ItemStack stack) {
		return stack.is(ItemTags.AXES);
	}

	@Override
	protected boolean usesTool() {
		return true;
	}

	@Override
	protected boolean usesBoneMeal() {
		return true;
	}

	/**
	 * Chopping time: 60 ticks bare-handed, faster with a better axe
	 * (wood 40, stone 24, iron ~17, diamond ~13, gold ~9).
	 */
	private int chopTicks(CopperGolem golem, BlockState log) {
		float speed = tool(golem).isEmpty() ? 1.0F : Math.max(1.0F, tool(golem).getDestroySpeed(log));
		return Math.max(MIN_CHOP_TICKS, Math.round(CHOP_TICKS * 2 / (1 + speed)));
	}

	private Job chopJob(ServerLevel level, CopperGolem golem, BlockPos base) {
		return new Job(Activity.CHOPPING, base, TREE_REACH, chopTicks(golem, level.getBlockState(base)),
			() -> isTrunkBase(level, base),
			tick -> {
				if (tick % CHOP_HIT_INTERVAL == 0) {
					chopEffects(level, base);
				}
			},
			() -> fell(level, golem, base));
	}

	private static boolean isTrunkBase(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).is(BlockTags.LOGS)
			&& level.getBlockState(pos.below()).is(BlockTags.DIRT);
	}

	private static void chopEffects(ServerLevel level, BlockPos base) {
		BlockState state = level.getBlockState(base);
		level.playSound(null, base, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
			base.getX() + 0.5, base.getY() + 0.5, base.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.0);
	}

	private void fell(ServerLevel level, CopperGolem golem, BlockPos base) {
		Tree tree = scanTree(level, base);
		if (tree == null) {
			return;
		}
		Identifier logId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(base).getBlock());

		for (BlockPos pos : tree.logs()) {
			collectDrops(level, golem, pos);
			level.destroyBlock(pos, false, golem);
		}
		damageTool(golem, tree.logs().size()); // like a player: one durability per log
		// Only the leaves that would now decay: a neighboring tree's canopy is still held by its own logs.
		Set<BlockPos> supported = stillSupported(level, tree.leaves());
		for (BlockPos pos : tree.leaves()) {
			if (!supported.contains(pos)) {
				collectDrops(level, golem, pos);
				level.removeBlock(pos, false);
			}
		}
		for (BlockPos stump : tree.stumps()) {
			replant(level, golem, stump, logId);
		}
	}

	/** Plants a sapling matching the felled tree (e.g. oak_log → oak_sapling), or any sapling if there's none. */
	private static void replant(ServerLevel level, CopperGolem golem, BlockPos pos, Identifier logId) {
		Identifier preferred = logId.withPath(logId.getPath().replace("_log", "_sapling"));
		SimpleContainer bag = GolemInventory.get(golem);
		int slot = -1;
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack stack = bag.getItem(i);
			if (stack.is(ItemTags.SAPLINGS) && stack.getItem() instanceof BlockItem) {
				if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(preferred)) {
					slot = i;
					break;
				}
				if (slot == -1) {
					slot = i;
				}
			}
		}
		if (slot == -1) {
			return;
		}
		BlockState sapling = ((BlockItem) bag.getItem(slot).getItem()).getBlock().defaultBlockState();
		if (level.getBlockState(pos).isAir() && sapling.canSurvive(level, pos)) {
			bag.removeItem(slot, 1);
			level.setBlockAndUpdate(pos, sapling);
			level.playSound(null, pos, sapling.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
			level.gameEvent(golem, GameEvent.BLOCK_PLACE, pos);
		}
	}

	/** Finds the tree growing from {@code base}, or null if it doesn't look like a natural tree. */
	static @Nullable Tree scanTree(ServerLevel level, BlockPos base) {
		// Logs: connected in any direction (including diagonals, for branches), not below the base.
		List<BlockPos> logs = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(base.immutable());
		seen.add(base.immutable());
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			BlockState state = level.getBlockState(pos);
			if (!state.is(BlockTags.LOGS) || isStripped(state)) {
				if (state.is(BlockTags.LOGS)) {
					return null; // stripped logs mean a player build
				}
				continue;
			}
			logs.add(pos);
			if (logs.size() > MAX_LOGS) {
				return null;
			}
			for (BlockPos next : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
				if (next.getY() >= base.getY() && withinSpread(base, next) && seen.add(next.immutable())) {
					if (level.getBlockState(next).is(BlockTags.LOGS)) {
						queue.add(next.immutable());
					}
				}
			}
		}

		List<BlockPos> stumps = logs.stream()
			.filter(pos -> pos.getY() == base.getY() && level.getBlockState(pos.below()).is(BlockTags.DIRT))
			.toList();
		if (stumps.size() > MAX_STUMPS || !fitsIn2x2(stumps)) {
			return null; // too wide at the bottom to be a trunk: probably a wall
		}

		// Leaves: natural (non-persistent) leaves touching the logs or each other.
		List<BlockPos> leaves = new ArrayList<>();
		Set<BlockPos> seenLeaves = new HashSet<>();
		ArrayDeque<BlockPos> leafQueue = new ArrayDeque<>();
		for (BlockPos log : logs) {
			for (Direction dir : Direction.values()) {
				BlockPos next = log.relative(dir);
				if (seenLeaves.add(next) && isNaturalLeaves(level.getBlockState(next))) {
					leafQueue.add(next);
				}
			}
		}
		while (!leafQueue.isEmpty() && leaves.size() < MAX_LEAVES) {
			BlockPos pos = leafQueue.poll();
			leaves.add(pos);
			for (Direction dir : Direction.values()) {
				BlockPos next = pos.relative(dir);
				if (next.getY() >= base.getY() && withinSpread(base, next) && seenLeaves.add(next)
					&& isNaturalLeaves(level.getBlockState(next))) {
					leafQueue.add(next);
				}
			}
		}
		if (leaves.size() < MIN_LEAVES) {
			return null; // no natural leaves: not a tree
		}
		return new Tree(logs, leaves, stumps);
	}

	/**
	 * Of the given leaves, those still within {@link #LEAF_SUPPORT_DISTANCE} blocks (through leaves)
	 * of a log, the same rule vanilla uses to decide which leaves decay. Call after removing a tree's
	 * logs: what remains supported belongs to other trees.
	 */
	private static Set<BlockPos> stillSupported(ServerLevel level, List<BlockPos> leaves) {
		Set<BlockPos> candidates = new HashSet<>(leaves);
		Set<BlockPos> supported = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		java.util.Map<BlockPos, Integer> distance = new java.util.HashMap<>();
		for (BlockPos leaf : leaves) {
			for (Direction dir : Direction.values()) {
				if (level.getBlockState(leaf.relative(dir)).is(BlockTags.LOGS)) {
					distance.put(leaf, 1);
					supported.add(leaf);
					queue.add(leaf);
					break;
				}
			}
		}
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			int next = distance.get(pos) + 1;
			if (next > LEAF_SUPPORT_DISTANCE) {
				continue;
			}
			for (Direction dir : Direction.values()) {
				BlockPos neighbor = pos.relative(dir);
				if (candidates.contains(neighbor) && !supported.contains(neighbor)) {
					distance.put(neighbor, next);
					supported.add(neighbor);
					queue.add(neighbor);
				}
			}
		}
		return supported;
	}

	private static boolean isStripped(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_");
	}

	private static boolean withinSpread(BlockPos base, BlockPos pos) {
		return Math.abs(pos.getX() - base.getX()) <= MAX_TREE_SPREAD && Math.abs(pos.getZ() - base.getZ()) <= MAX_TREE_SPREAD;
	}

	private static boolean fitsIn2x2(List<BlockPos> stumps) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : stumps) {
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		return stumps.isEmpty() || (maxX - minX <= 1 && maxZ - minZ <= 1);
	}
}
