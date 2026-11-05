package com.smartergolems.test;

import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class FarmingGameTest {
	private static final List<BlockPos> CROPS = List.of(new BlockPos(2, 1, 5), new BlockPos(3, 1, 5), new BlockPos(4, 1, 5));
	private static final BlockPos CHEST = new BlockPos(1, 1, 1);
	private static final BlockPos GOLEM = new BlockPos(3, 1, 3);

	/** A farming golem harvests ripe wheat, replants it, and deposits the harvest into a chest. */
	@GameTest(maxTicks = 1200)
	public void harvestsReplantsAndDeposits(GameTestHelper helper) {
		buildFloor(helper);
		for (BlockPos crop : CROPS) {
			helper.setBlock(crop.below(), Blocks.FARMLAND);
			helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
		}
		helper.setBlock(CHEST, Blocks.CHEST);

		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		GolemTasks.set(golem, new GolemTask(GolemTaskType.FARMING, golem.blockPosition()));

		helper.succeedWhen(() -> {
			for (BlockPos crop : CROPS) {
				helper.assertBlockState(crop,
					state -> state.is(Blocks.WHEAT) && !isRipe(state),
					state -> net.minecraft.network.chat.Component.literal("Expected replanted (unripe) wheat at " + crop + ", found " + state));
			}
			ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			helper.assertTrue(chest.countItem(Items.WHEAT) >= CROPS.size(),
				"Expected at least " + CROPS.size() + " wheat in the chest, found " + chest.countItem(Items.WHEAT));
		});
	}

	/** A golem with no task is left alone: ripe wheat is not touched. */
	@GameTest(maxTicks = 200)
	public void golemWithoutTaskDoesNotFarm(GameTestHelper helper) {
		buildFloor(helper);
		BlockPos crop = CROPS.get(1);
		helper.setBlock(crop.below(), Blocks.FARMLAND);
		helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
		helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);

		helper.runAtTickTime(150, () -> {
			helper.assertBlockState(crop, FarmingGameTest::isRipe,
				state -> net.minecraft.network.chat.Component.literal("Wheat without a farming golem should stay ripe, found " + state));
			helper.succeed();
		});
	}

	private static void buildFloor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
	}

	private static boolean isRipe(BlockState state) {
		return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
	}
}
