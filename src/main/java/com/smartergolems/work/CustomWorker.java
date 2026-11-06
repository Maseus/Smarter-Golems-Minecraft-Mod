package com.smartergolems.work;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import com.smartergolems.task.TaughtBlock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Custom task, taught by example: mines the blocks the player broke while teaching, each time
 * the same kind of block is back at that spot (e.g. a cobblestone generator).
 *
 * <p>Drops follow vanilla tool rules, so a block that needs a pickaxe is only mined once the
 * golem holds one (it takes one from its chest). Mining time works like a player's: block
 * hardness divided by tool speed. Right after mining it waits by the spot for a while, so a
 * generator refilling doesn't send it to the chest after every block.
 */
public class CustomWorker extends TaskWorker {
	private static final double MINE_REACH = 2.0;
	private static final int MIN_MINE_TICKS = 5;
	private static final int HIT_INTERVAL = 5;
	/** How long to wait at a spot it just mined for the block to come back. */
	private static final int REFILL_WAIT_TICKS = 200;
	/** Length of one "wait for the block to come back" job; the golem checks again after it. */
	private static final int WAIT_JOB_TICKS = 10;

	private List<TaughtBlock> taught = List.of();
	/** When each spot was last mined, to wait for it to refill. */
	private final Map<BlockPos, Long> minedAt = new HashMap<>();

	@Override
	protected void prepare(GolemTask task) {
		taught = task.taught();
	}

	@Override
	protected Optional<Job> findWork(ServerLevel level, CopperGolem golem, GolemTask task) {
		BlockPos from = golem.blockPosition();
		Optional<Job> mine = taught.stream()
			.filter(spot -> !isSkipped(spot.pos()) && canMine(level, golem, spot))
			.min(Comparator.comparingDouble(spot -> spot.pos().distSqr(from)))
			.map(spot -> mineJob(level, golem, spot));
		if (mine.isPresent()) {
			return mine;
		}
		// Nothing to mine right now: if a spot was just mined, wait next to it for it to refill.
		long now = level.getGameTime();
		minedAt.values().removeIf(time -> now - time > REFILL_WAIT_TICKS);
		return minedAt.keySet().stream()
			.filter(pos -> !isSkipped(pos))
			.min(Comparator.comparingDouble(pos -> pos.distSqr(from)))
			.map(pos -> new Job(Activity.MINING, pos, MINE_REACH, WAIT_JOB_TICKS, () -> true, null, null));
	}

	/**
	 * A tool is worth holding if it gets drops from every taught block that needs a tool, and
	 * speeds up at least one of them (pickaxe for a cobblestone generator, shovel for clay).
	 */
	@Override
	public boolean wantsTool(ItemStack stack) {
		if (taught.isEmpty()) {
			// Not taught yet (e.g. while the task is being assigned): keep any digging tool.
			return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.HOES);
		}
		boolean speedsUp = false;
		for (TaughtBlock spot : taught) {
			BlockState state = spot.block().defaultBlockState();
			if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) {
				return false;
			}
			speedsUp |= stack.getDestroySpeed(state) > 1.0F;
		}
		return speedsUp;
	}

	@Override
	protected boolean usesTool() {
		return true;
	}

	/** The taught block is there, may be mined, and the golem's tool gets its drops. */
	private static boolean canMine(ServerLevel level, CopperGolem golem, TaughtBlock spot) {
		BlockState state = level.getBlockState(spot.pos());
		return state.is(spot.block())
			&& isMineable(level, spot.pos(), state)
			&& (!state.requiresCorrectToolForDrops() || golem.getMainHandItem().isCorrectToolForDrops(state));
	}

	/** Never air or fluids, unbreakable blocks (bedrock) or blocks holding items or data (chests, spawners). */
	public static boolean isMineable(ServerLevel level, BlockPos pos, BlockState state) {
		return !state.isAir()
			&& state.getFluidState().isEmpty()
			&& state.getDestroySpeed(level, pos) >= 0
			&& !state.hasBlockEntity();
	}

	/** Like a player: hardness × 1.5 s with the right tool (× 5 s without), divided by the tool's speed. */
	private static int mineTicks(ServerLevel level, CopperGolem golem, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		ItemStack tool = golem.getMainHandItem();
		float speed = Math.max(1.0F, tool.getDestroySpeed(state));
		boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		float ticks = state.getDestroySpeed(level, pos) * (correct ? 30 : 100) / speed;
		return Math.max(MIN_MINE_TICKS, Math.round(ticks));
	}

	private Job mineJob(ServerLevel level, CopperGolem golem, TaughtBlock spot) {
		BlockPos pos = spot.pos();
		int ticks = mineTicks(level, golem, pos);
		return new Job(Activity.MINING, pos, MINE_REACH, ticks,
			() -> canMine(level, golem, spot),
			tick -> {
				if (tick % HIT_INTERVAL == 0) {
					hitEffects(level, pos);
				}
				// The crack overlay players see while mining, keyed to this golem.
				int progress = Math.min(9, tick * 10 / Math.max(1, WorkSpeed.scaleTicks(golem, ticks)));
				level.destroyBlockProgress(golem.getId(), pos, progress);
			},
			() -> mine(level, golem, spot));
	}

	private void mine(ServerLevel level, CopperGolem golem, TaughtBlock spot) {
		BlockPos pos = spot.pos();
		level.destroyBlockProgress(golem.getId(), pos, -1);
		if (!canMine(level, golem, spot)) {
			return; // changed while the golem was working on it
		}
		BlockState state = level.getBlockState(pos);
		float hardness = state.getDestroySpeed(level, pos);
		List<ItemStack> drops = Block.getDrops(state, level, pos, null, golem, golem.getMainHandItem());
		level.destroyBlock(pos, false, golem);
		for (ItemStack drop : drops) {
			GolemInventory.addOrDrop(golem, level, drop);
		}
		if (hardness > 0) {
			damageTool(golem, 1); // like a player: instant-break blocks don't wear tools
		}
		minedAt.put(pos, level.getGameTime());
	}

	private static void hitEffects(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.5F, 0.8F);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
			pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
	}
}
