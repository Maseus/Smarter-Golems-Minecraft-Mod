package com.smartergolems.work;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Farming: harvest fully grown crops and replant them, and plant carried seeds on empty farmland.
 */
public class FarmingWorker extends TaskWorker {
	private static final double CROP_REACH = 1.5;

	@Override
	protected Optional<Job> findWork(ServerLevel level, CopperGolem golem, GolemTask task) {
		Optional<BlockPos> ripe = findNearest(golem, task.areaPositions(), pos -> {
			BlockState state = level.getBlockState(pos);
			return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
		});
		if (ripe.isPresent()) {
			BlockPos pos = ripe.get();
			return Optional.of(Job.instant(pos, CROP_REACH, () -> harvest(level, golem, pos)));
		}
		if (findSeed(golem).isPresent()) {
			Optional<BlockPos> farmland = findNearest(golem, task.areaPositions(), pos -> isEmptyFarmland(level, pos));
			if (farmland.isPresent()) {
				BlockPos pos = farmland.get();
				return Optional.of(Job.instant(pos, CROP_REACH, () -> plant(level, golem, pos)));
			}
		}
		return Optional.empty();
	}

	/** Keep some seeds for replanting empty farmland. */
	@Override
	protected int amountToKeep(ItemStack stack) {
		return isSeed(stack) ? KEEP_AMOUNT : 0;
	}

	private static boolean isSeed(ItemStack stack) {
		return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof CropBlock;
	}

	private static boolean isEmptyFarmland(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(Blocks.FARMLAND);
	}

	private static void harvest(ServerLevel level, CopperGolem golem, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) {
			return;
		}
		List<ItemStack> drops = Block.getDrops(state, level, pos, null, golem, ItemStack.EMPTY);
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
		if (!replanted && takeFromBag(golem, seed)) {
			placeCrop(level, golem, pos, crop);
		}
	}

	private static void plant(ServerLevel level, CopperGolem golem, BlockPos pos) {
		if (!isEmptyFarmland(level, pos)) {
			return;
		}
		findSeed(golem).ifPresent(crop -> {
			if (crop.getStateForAge(0).canSurvive(level, pos) && takeFromBag(golem, crop.asItem())) {
				placeCrop(level, golem, pos, crop);
			}
		});
	}

	private static boolean placeCrop(ServerLevel level, CopperGolem golem, BlockPos pos, CropBlock crop) {
		BlockState planted = crop.getStateForAge(0);
		if (!planted.canSurvive(level, pos)) {
			return false;
		}
		level.setBlockAndUpdate(pos, planted);
		level.playSound(null, pos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
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
