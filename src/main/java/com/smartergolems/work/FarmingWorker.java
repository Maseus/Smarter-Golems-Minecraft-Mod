package com.smartergolems.work;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Farming, in order of priority:
 * <ol>
 * <li>Harvest anything ripe: crops (wheat, carrots, potatoes, beetroot), nether wart, sweet berries,
 *     cocoa, pumpkins/melons grown from a stem, and sugar cane/cactus/bamboo (leaving the bottom block).
 *     Crops, nether wart, berries and cocoa are replanted / left to regrow.</li>
 * <li>Plant carried seeds on empty farmland, and nether wart on empty soul sand.</li>
 * <li>With a hoe: till dirt/grass next to the farm (near water) to grow it, while it has seeds to plant.</li>
 * <li>With bone meal: fertilize growing crops.</li>
 * </ol>
 */
public class FarmingWorker extends TaskWorker {
	private static final double CROP_REACH = 1.5;
	/** Farmland stays wet within this many blocks of water (same as vanilla). */
	private static final int WATER_RANGE = 4;

	@Override
	public boolean wantsTool(ItemStack stack) {
		return stack.is(ItemTags.HOES);
	}

	@Override
	protected boolean usesBoneMeal() {
		return true;
	}

	/** Keep some seeds (and nether wart) for replanting empty soil. */
	@Override
	protected int amountToKeep(ItemStack stack) {
		return isSeed(stack) ? KEEP_AMOUNT : super.amountToKeep(stack);
	}

	@Override
	protected Optional<Job> findWork(ServerLevel level, CopperGolem golem, GolemTask task) {
		Optional<BlockPos> ripe = findNearest(golem, task.areaPositions(), pos -> isHarvestable(level, pos));
		if (ripe.isPresent()) {
			BlockPos pos = ripe.get();
			return Optional.of(Job.instant(Activity.HARVESTING, pos, CROP_REACH, () -> harvest(level, golem, pos)));
		}
		Optional<BlockPos> soil = findNearest(golem, task.areaPositions(), pos -> seedFor(golem, level, pos).isPresent());
		if (soil.isPresent()) {
			BlockPos pos = soil.get();
			return Optional.of(Job.instant(Activity.PLANTING, pos, CROP_REACH, () -> plant(level, golem, pos)));
		}
		if (!tool(golem).isEmpty() && findCropSeed(golem).isPresent()) {
			Optional<BlockPos> tillable = findNearest(golem, task.areaPositions(), pos -> isTillable(level, pos));
			if (tillable.isPresent()) {
				BlockPos pos = tillable.get();
				return Optional.of(Job.instant(Activity.TILLING, pos, CROP_REACH, () -> till(level, golem, pos)));
			}
		}
		if (boneMealSlot(golem) >= 0) {
			Optional<BlockPos> growing = findNearest(golem, task.areaPositions(), pos -> isFertilizable(level, pos));
			if (growing.isPresent()) {
				BlockPos pos = growing.get();
				return Optional.of(Job.instant(Activity.FERTILIZING, pos, CROP_REACH, () -> fertilize(level, golem, pos)));
			}
		}
		return Optional.empty();
	}

	// ---- Harvesting ----

	private static boolean isHarvestable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof CropBlock crop) {
			return crop.isMaxAge(state);
		}
		if (block instanceof NetherWartBlock) {
			return state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
		}
		if (block instanceof SweetBerryBushBlock) {
			return state.getValue(SweetBerryBushBlock.AGE) >= SweetBerryBushBlock.MAX_AGE;
		}
		if (block instanceof CocoaBlock) {
			return state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
		}
		if (state.is(Blocks.PUMPKIN) || state.is(Blocks.MELON)) {
			return isGrownFromStem(level, pos);
		}
		if (isTallPlant(state)) {
			// The second block from the bottom: harvest it and everything above, keep the base.
			return level.getBlockState(pos.below()).is(block) && !level.getBlockState(pos.below(2)).is(block);
		}
		return false;
	}

	/** A pumpkin/melon an attached stem points at, so player-placed pumpkins and melons are left alone. */
	private static boolean isGrownFromStem(ServerLevel level, BlockPos pos) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockState neighbor = level.getBlockState(pos.relative(dir));
			if (neighbor.getBlock() instanceof AttachedStemBlock && neighbor.getValue(AttachedStemBlock.FACING) == dir.getOpposite()) {
				return true;
			}
		}
		return false;
	}

	private static boolean isTallPlant(BlockState state) {
		return state.is(Blocks.SUGAR_CANE) || state.is(Blocks.CACTUS) || state.is(Blocks.BAMBOO);
	}

	private void harvest(ServerLevel level, CopperGolem golem, BlockPos pos) {
		if (!isHarvestable(level, pos)) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof CropBlock crop) {
			harvestAndReplant(level, golem, pos, state, crop.asItem(), crop.getStateForAge(0));
		} else if (block instanceof NetherWartBlock) {
			harvestAndReplant(level, golem, pos, state, Items.NETHER_WART, block.defaultBlockState());
		} else if (block instanceof SweetBerryBushBlock) {
			// Like a player picking berries: the bush stays and regrows.
			int count = 2 + level.getRandom().nextInt(2);
			GolemInventory.addOrDrop(golem, level, new ItemStack(Items.SWEET_BERRIES, count));
			level.setBlockAndUpdate(pos, state.setValue(SweetBerryBushBlock.AGE, 1));
			level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0F, 1.0F);
			level.gameEvent(golem, GameEvent.BLOCK_CHANGE, pos);
		} else if (block instanceof CocoaBlock) {
			collectDrops(level, golem, pos);
			level.destroyBlock(pos, false, golem);
			level.setBlockAndUpdate(pos, state.setValue(CocoaBlock.AGE, 0)); // same spot and facing
		} else if (isTallPlant(state)) {
			// Break from the top down, so the upper blocks don't pop off as loose items.
			BlockPos top = pos;
			while (level.getBlockState(top.above()).is(block)) {
				top = top.above();
			}
			for (BlockPos p = top; p.getY() >= pos.getY(); p = p.below()) {
				collectDrops(level, golem, p);
				level.destroyBlock(p, false, golem);
			}
		} else {
			// Pumpkin or melon: the stem stays and grows a new one.
			collectDrops(level, golem, pos);
			level.destroyBlock(pos, false, golem);
		}
	}

	/** Breaks a crop, replants it using a seed from its own drops (or the bag), and keeps the rest. */
	private static void harvestAndReplant(ServerLevel level, CopperGolem golem, BlockPos pos, BlockState state, Item seed, BlockState planted) {
		List<ItemStack> drops = Block.getDrops(state, level, pos, null, golem, ItemStack.EMPTY);
		level.destroyBlock(pos, false, golem);
		// If the soil is gone (e.g. trampled farmland), keep every seed instead of losing one.
		boolean canReplant = planted.canSurvive(level, pos);
		boolean replanted = false;
		for (ItemStack drop : drops) {
			if (canReplant && !replanted && drop.is(seed)) {
				drop.shrink(1);
				replanted = place(level, golem, pos, planted);
			}
		}
		for (ItemStack drop : drops) {
			if (!drop.isEmpty()) {
				GolemInventory.addOrDrop(golem, level, drop);
			}
		}
		if (canReplant && !replanted && takeFromBag(golem, seed)) {
			place(level, golem, pos, planted);
		}
	}

	// ---- Planting ----

	/** The seed (as the block it plants) the golem carries that can go at this empty spot, if any. */
	private static Optional<BlockState> seedFor(CopperGolem golem, ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos).isAir()) {
			return Optional.empty();
		}
		BlockState soil = level.getBlockState(pos.below());
		if (soil.is(Blocks.FARMLAND)) {
			return findCropSeed(golem).map(crop -> crop.getStateForAge(0));
		}
		if (soil.is(Blocks.SOUL_SAND) && bagContains(golem, Items.NETHER_WART)) {
			return Optional.of(Blocks.NETHER_WART.defaultBlockState());
		}
		return Optional.empty();
	}

	private void plant(ServerLevel level, CopperGolem golem, BlockPos pos) {
		seedFor(golem, level, pos).ifPresent(planted -> {
			if (planted.canSurvive(level, pos) && takeFromBag(golem, planted.getBlock().asItem())) {
				place(level, golem, pos, planted);
			}
		});
	}

	private static boolean place(ServerLevel level, CopperGolem golem, BlockPos pos, BlockState planted) {
		if (!planted.canSurvive(level, pos)) {
			return false;
		}
		level.setBlockAndUpdate(pos, planted);
		level.playSound(null, pos, planted.is(Blocks.NETHER_WART) ? SoundEvents.NETHER_WART_PLANTED : SoundEvents.CROP_PLANTED,
			SoundSource.BLOCKS, 1.0F, 1.0F);
		level.gameEvent(golem, GameEvent.BLOCK_PLACE, pos);
		return true;
	}

	// ---- Tilling (needs a hoe) ----

	/**
	 * Dirt or grass with air above, right next to existing farmland and close enough to water to stay
	 * wet. This grows the farm outward from what the player built instead of tilling every lawn.
	 */
	private static boolean isTillable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)) || !level.getBlockState(pos.above()).isAir()) {
			return false;
		}
		boolean nextToFarmland = false;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			nextToFarmland |= level.getBlockState(pos.relative(dir)).is(Blocks.FARMLAND);
		}
		return nextToFarmland && isNearWater(level, pos);
	}

	private static boolean isNearWater(ServerLevel level, BlockPos pos) {
		for (BlockPos p : BlockPos.betweenClosed(pos.offset(-WATER_RANGE, 0, -WATER_RANGE), pos.offset(WATER_RANGE, 1, WATER_RANGE))) {
			if (level.getFluidState(p).is(FluidTags.WATER)) {
				return true;
			}
		}
		return false;
	}

	/** Tills the block, then the planting step fills the new farmland on a later tick. */
	private void till(ServerLevel level, CopperGolem golem, BlockPos pos) {
		if (!isTillable(level, pos) || tool(golem).isEmpty()) {
			return;
		}
		level.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
		level.playSound(null, pos, SoundEvents.HOE_TILL.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
		level.gameEvent(golem, GameEvent.BLOCK_CHANGE, pos);
		damageTool(golem, 1);
	}

	// ---- Bone meal ----

	private static boolean isFertilizable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof CropBlock crop) {
			return !crop.isMaxAge(state);
		}
		if (block instanceof StemBlock) {
			return state.getValue(StemBlock.AGE) < StemBlock.MAX_AGE;
		}
		if (block instanceof SweetBerryBushBlock) {
			return state.getValue(SweetBerryBushBlock.AGE) < SweetBerryBushBlock.MAX_AGE;
		}
		if (block instanceof CocoaBlock) {
			return state.getValue(CocoaBlock.AGE) < CocoaBlock.MAX_AGE;
		}
		return false;
	}

	private void fertilize(ServerLevel level, CopperGolem golem, BlockPos pos) {
		int slot = boneMealSlot(golem);
		if (slot < 0 || !isFertilizable(level, pos)) {
			return;
		}
		SimpleContainer bag = GolemInventory.get(golem);
		if (BoneMealItem.growCrop(bag.getItem(slot), level, pos)) {
			bag.setChanged();
			level.levelEvent(1505, pos, 15); // bone meal particles
		}
	}

	// ---- Bag helpers ----

	private static boolean isSeed(ItemStack stack) {
		return stack.is(Items.NETHER_WART)
			|| (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof CropBlock);
	}

	/** The crop block of the first crop seed (wheat seeds, carrot, ...) in the golem's bag. */
	private static Optional<CropBlock> findCropSeed(CopperGolem golem) {
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

	private static boolean bagContains(CopperGolem golem, Item item) {
		if (GolemInventory.isEmpty(golem)) {
			return false;
		}
		for (ItemStack stack : GolemInventory.get(golem).getItems()) {
			if (stack.is(item)) {
				return true;
			}
		}
		return false;
	}

	/** Removes one of the given item from the bag; false if there was none. */
	private static boolean takeFromBag(CopperGolem golem, Item item) {
		SimpleContainer bag = GolemInventory.get(golem);
		for (int i = 0; i < bag.getContainerSize(); i++) {
			if (bag.getItem(i).is(item)) {
				bag.removeItem(i, 1);
				return true;
			}
		}
		return false;
	}
}
