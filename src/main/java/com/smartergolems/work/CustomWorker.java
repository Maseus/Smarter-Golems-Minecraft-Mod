package com.smartergolems.work;

import com.mojang.authlib.GameProfile;
import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import com.smartergolems.task.TaughtBlock;
import com.smartergolems.task.TaughtUse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Custom task, taught by example. The golem repeats what the player showed it:
 * <ul>
 * <li><b>Mining</b>: each taught spot, whenever the same kind of block is back there (e.g. a
 * cobblestone generator). Drops follow vanilla tool rules, so a block that needs a pickaxe is
 * only mined once the golem holds one (it takes one from its chest). Mining time works like a
 * player's: block hardness divided by tool speed.</li>
 * <li><b>Right-clicks</b>: placing a block, using an item on a block or at a fluid, whenever
 * that spot is exactly as it was when taught (e.g. place dirt on empty air, use a water bottle
 * on the dirt). The golem uses the item through a fake player standing where the teacher stood,
 * so every item behaves as it does for players. Items come from its chest.</li>
 * </ul>
 * Each step matches only one state of its spot, so the steps of a cycle (place dirt, make mud,
 * wait for clay, mine it) happen in the right order without the golem remembering anything.
 * Right after mining it waits by the spot for a while, so a generator refilling doesn't send it
 * to the chest after every block.
 */
public class CustomWorker extends TaskWorker {
	private static final double WORK_REACH = 2.0;
	private static final int MIN_MINE_TICKS = 5;
	private static final int HIT_INTERVAL = 5;
	private static final int USE_TICKS = 8;
	/** How long to wait at a spot it just mined for the block to come back. */
	private static final int REFILL_WAIT_TICKS = 200;
	/** Length of one "wait for the block to come back" job; the golem checks again after it. */
	private static final int WAIT_JOB_TICKS = 10;
	/**
	 * Our own fake player rather than Fabric's shared default one: its inventory is cleared
	 * around every use, which must never touch another mod's items.
	 */
	private static final GameProfile FAKE_PLAYER_PROFILE = new GameProfile(
		UUID.nameUUIDFromBytes("smartergolems:copper_golem".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "[Copper Golem]");

	private List<TaughtBlock> taught = List.of();
	private List<TaughtUse> uses = List.of();
	private List<ItemStack> supplies = List.of();
	/** When each spot was last mined, to wait for it to refill. */
	private final Map<BlockPos, Long> minedAt = new HashMap<>();
	/** The block showing this golem's crack overlay, if it's mining one. */
	private @Nullable BlockPos cracking;

	@Override
	public void prepare(GolemTask task) {
		if (task.uses() != uses) {
			// The items the taught right-clicks use, once each.
			List<ItemStack> samples = new ArrayList<>();
			for (TaughtUse use : task.uses()) {
				if (!use.item().isEmpty() && samples.stream().noneMatch(s -> matchesSupply(use.item(), s))) {
					samples.add(use.item());
				}
			}
			supplies = List.copyOf(samples);
		}
		taught = task.taught();
		uses = task.uses();
	}

	@Override
	protected List<ItemStack> supplies() {
		return supplies;
	}

	/** Also forget recently mined spots (they may not be taught anymore) and clear any crack overlay. */
	@Override
	public void reset(ServerLevel level, CopperGolem golem) {
		super.reset(level, golem);
		minedAt.clear();
		clearCracks(level, golem);
	}

	private void clearCracks(ServerLevel level, CopperGolem golem) {
		if (cracking != null) {
			level.destroyBlockProgress(golem.getId(), cracking, -1);
			cracking = null;
		}
	}

	@Override
	protected Optional<Job> findWork(ServerLevel level, CopperGolem golem, GolemTask task) {
		BlockPos from = golem.blockPosition();
		Optional<TaughtBlock> mine = taught.stream()
			.filter(spot -> !isSkipped(spot.pos()) && canMine(level, golem, spot))
			.min(Comparator.comparingDouble(spot -> spot.pos().distSqr(from)));
		Optional<TaughtUse> use = uses.stream()
			.filter(step -> !isSkipped(step.pos()) && canUse(level, golem, step))
			.min(Comparator.comparingDouble(step -> step.pos().distSqr(from)));
		if (mine.isPresent() && (use.isEmpty() || mine.get().pos().distSqr(from) <= use.get().pos().distSqr(from))) {
			return Optional.of(mineJob(level, golem, mine.get()));
		}
		if (use.isPresent()) {
			return Optional.of(useJob(level, golem, use.get()));
		}
		// Nothing to do right now: if a spot was just mined, wait next to it for it to refill.
		long now = level.getGameTime();
		minedAt.values().removeIf(time -> now - time > REFILL_WAIT_TICKS);
		return minedAt.keySet().stream()
			.filter(pos -> !isSkipped(pos))
			.min(Comparator.comparingDouble(pos -> pos.distSqr(from)))
			.map(pos -> new Job(Activity.MINING, pos, WORK_REACH, WAIT_JOB_TICKS, () -> true, null, null));
	}

	/**
	 * A tool is worth holding if it gets drops from every taught block that needs a tool, and
	 * speeds up at least one of them (pickaxe for a cobblestone generator, shovel for clay).
	 */
	@Override
	public boolean wantsTool(ItemStack stack) {
		if (taught.isEmpty()) {
			// Nothing to mine: no tool needed, but while the task is being assigned (nothing
			// known yet) keep any digging tool rather than putting it away.
			return uses.isEmpty() && (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.HOES));
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
		return !taught.isEmpty();
	}

	// ---- Mining ----

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
		return new Job(Activity.MINING, pos, WORK_REACH, ticks,
			() -> canMine(level, golem, spot),
			tick -> {
				if (tick % HIT_INTERVAL == 0) {
					hitEffects(level, pos);
				}
				// The crack overlay players see while mining, keyed to this golem.
				int progress = Math.min(9, tick * 10 / Math.max(1, WorkSpeed.scaleTicks(golem, ticks)));
				level.destroyBlockProgress(golem.getId(), pos, progress);
				cracking = pos;
			},
			() -> mine(level, golem, spot));
	}

	private void mine(ServerLevel level, CopperGolem golem, TaughtBlock spot) {
		BlockPos pos = spot.pos();
		clearCracks(level, golem);
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

	// ---- Right-clicks ----

	/** The spot is exactly as it was when taught, and the golem carries the item (if one is needed). */
	private static boolean canUse(ServerLevel level, CopperGolem golem, TaughtUse step) {
		return level.getBlockState(step.pos()).equals(step.before())
			&& (step.item().isEmpty() || bagSlot(golem, step.item()) >= 0);
	}

	private static int bagSlot(CopperGolem golem, ItemStack sample) {
		if (GolemInventory.isEmpty(golem)) {
			return -1;
		}
		SimpleContainer bag = GolemInventory.get(golem);
		for (int i = 0; i < bag.getContainerSize(); i++) {
			if (!bag.getItem(i).isEmpty() && matchesSupply(bag.getItem(i), sample)) {
				return i;
			}
		}
		return -1;
	}

	private Job useJob(ServerLevel level, CopperGolem golem, TaughtUse step) {
		boolean placing = step.onBlock() && step.item().getItem() instanceof BlockItem && !step.pos().equals(step.clicked());
		return new Job(placing ? Activity.PLACING : Activity.USING, step.pos(), WORK_REACH, USE_TICKS,
			() -> canUse(level, golem, step), null, () -> use(level, golem, step));
	}

	/**
	 * Uses one of the item the way the player did, through a fake player standing where they
	 * stood. Whatever the fake player ends up holding (an empty bottle, a filled bucket, or the
	 * item itself if nothing happened) goes back into the bag. A step that does nothing is
	 * skipped for a while, so the golem doesn't keep trying.
	 */
	private void use(ServerLevel level, CopperGolem golem, TaughtUse step) {
		if (!canUse(level, golem, step)) {
			return;
		}
		ItemStack item = ItemStack.EMPTY;
		if (!step.item().isEmpty()) {
			SimpleContainer bag = GolemInventory.get(golem);
			item = bag.removeItem(bagSlot(golem, step.item()), 1);
			bag.setChanged();
		}
		ItemStack given = item.copy();

		FakePlayer player = FakePlayer.get(level, FAKE_PLAYER_PROFILE);
		Inventory inventory = player.getInventory();
		inventory.clearContent();
		player.snapTo(step.from().x, step.from().y, step.from().z, step.yRot(), step.xRot());
		player.setYHeadRot(step.yRot());
		player.setShiftKeyDown(false);
		player.setItemInHand(InteractionHand.MAIN_HAND, item);
		if (step.onBlock()) {
			player.gameMode.useItemOn(player, level, item, InteractionHand.MAIN_HAND, step.hitResult());
		} else {
			player.gameMode.useItem(player, level, item, InteractionHand.MAIN_HAND);
		}
		player.stopUsingItem(); // e.g. a bottle it started drinking

		List<ItemStack> returned = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (!inventory.getItem(i).isEmpty()) {
				returned.add(inventory.getItem(i).copy());
			}
		}
		inventory.clearContent();
		returned.forEach(stack -> GolemInventory.addOrDrop(golem, level, stack));

		boolean blockChanged = !level.getBlockState(step.pos()).equals(step.before());
		boolean itemChanged = returned.size() != (given.isEmpty() ? 0 : 1)
			|| (!given.isEmpty() && !ItemStack.matches(returned.getFirst(), given));
		if (!blockChanged && !itemChanged) {
			skip(level, step.pos());
		}
	}
}
