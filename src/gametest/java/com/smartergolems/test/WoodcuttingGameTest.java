package com.smartergolems.test;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import com.smartergolems.work.WorkSpeed;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class WoodcuttingGameTest {
	private static final BlockPos TRUNK_BASE = new BlockPos(5, 1, 5);
	private static final int TRUNK_HEIGHT = 4;
	private static final BlockPos CHEST = new BlockPos(1, 1, 6);
	private static final BlockPos GOLEM = new BlockPos(2, 1, 2);

	/** A woodcutting golem fells a tree, deposits the logs, and replants a sapling on the stump. */
	@GameTest(maxTicks = 1200, padding = TestUtil.PADDING)
	public void fellsTreeDepositsAndReplants(GameTestHelper helper) {
		buildFloor(helper);
		buildOakTree(helper);
		helper.setBlock(CHEST, Blocks.CHEST);

		CopperGolem golem = spawnWoodcutter(helper);
		// Leaves only drop saplings by chance, so give the golem one to make replanting certain.
		GolemInventory.get(golem).addItem(new ItemStack(Items.OAK_SAPLING));

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_SAPLING, TRUNK_BASE);
			for (int y = 1; y < TRUNK_HEIGHT; y++) {
				helper.assertBlockPresent(Blocks.AIR, TRUNK_BASE.above(y));
			}
			ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			helper.assertTrue(chest.countItem(Items.OAK_LOG) >= TRUNK_HEIGHT,
				"Expected at least " + TRUNK_HEIGHT + " oak logs in the chest, found " + chest.countItem(Items.OAK_LOG));
		});
	}

	/** A trunk fully walled in by natural leaves (like a dense forest) is still reached and felled. */
	@GameTest(maxTicks = 1200, padding = TestUtil.PADDING)
	public void pushesThroughLeaves(GameTestHelper helper) {
		buildFloor(helper);
		buildOakTree(helper);
		// A 2-high ring of natural leaves hugging the trunk, so there's no path to it. The leaves touch the
		// trunk so they don't decay during the test (natural leaves far from logs decay on random ticks).
		BlockState leaves = naturalLeaves();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					helper.setBlock(TRUNK_BASE.offset(dx, 0, dz), leaves);
					helper.setBlock(TRUNK_BASE.offset(dx, 1, dz), leaves);
				}
			}
		}
		CopperGolem golem = spawnWoodcutter(helper);
		GolemInventory.get(golem).addItem(new ItemStack(Items.OAK_SAPLING));

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_SAPLING, TRUNK_BASE);
			helper.assertBlockPresent(Blocks.AIR, TRUNK_BASE.above());
		});
	}

	/** Player-placed (persistent) leaves are never broken, even when they block the way. */
	@GameTest(maxTicks = 400, padding = TestUtil.PADDING)
	public void leavesPlacedLeavesAlone(GameTestHelper helper) {
		buildFloor(helper);
		buildOakTree(helper);
		BlockState hedge = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
					helper.setBlock(TRUNK_BASE.offset(dx, 0, dz), hedge);
					helper.setBlock(TRUNK_BASE.offset(dx, 1, dz), hedge);
				}
			}
		}
		spawnWoodcutter(helper);

		helper.runAtTickTime(300, () -> {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
						helper.assertBlockPresent(Blocks.OAK_LEAVES, TRUNK_BASE.offset(dx, 0, dz));
						helper.assertBlockPresent(Blocks.OAK_LEAVES, TRUNK_BASE.offset(dx, 1, dz));
					}
				}
			}
			helper.succeed();
		});
	}

	/** Logs without natural leaves (like a player's house) are never cut. */
	@GameTest(maxTicks = 400, padding = TestUtil.PADDING)
	public void leavesBuildsAlone(GameTestHelper helper) {
		buildFloor(helper);
		for (int y = 0; y < TRUNK_HEIGHT; y++) {
			helper.setBlock(TRUNK_BASE.above(y), Blocks.OAK_LOG);
		}
		spawnWoodcutter(helper);

		helper.runAtTickTime(300, () -> {
			for (int y = 0; y < TRUNK_HEIGHT; y++) {
				helper.assertBlockPresent(Blocks.OAK_LOG, TRUNK_BASE.above(y));
			}
			helper.succeed();
		});
	}

	/** A wide log wall is never cut, even with natural leaves touching it (e.g. a tree grown against a house). */
	@GameTest(maxTicks = 400, padding = TestUtil.PADDING)
	public void leavesWideWallsAlone(GameTestHelper helper) {
		buildFloor(helper);
		BlockState leaves = naturalLeaves();
		for (int x = 3; x <= 6; x++) {
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(x, y, 5, Blocks.OAK_LOG);
			}
			helper.setBlock(x, 4, 5, leaves);
			helper.setBlock(x, 3, 6, leaves);
		}
		spawnWoodcutter(helper);

		helper.runAtTickTime(300, () -> {
			for (int x = 3; x <= 6; x++) {
				for (int y = 1; y <= 3; y++) {
					helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(x, y, 5));
				}
			}
			helper.succeed();
		});
	}

	/** Each oxidation stage makes the golem slower. */
	@GameTest(padding = TestUtil.PADDING)
	public void oxidationSlowsGolemDown(GameTestHelper helper) {
		buildFloor(helper);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		double previous = Double.MAX_VALUE;
		for (WeatheringCopper.WeatherState state : WeatheringCopper.WeatherState.values()) {
			golem.setWeatherState(state);
			double speed = WorkSpeed.multiplier(golem);
			helper.assertTrue(speed < previous, Component.literal(state + " should be slower than the previous stage, got " + speed));
			previous = speed;
		}
		helper.succeed();
	}

	private static CopperGolem spawnWoodcutter(GameTestHelper helper) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		GolemTasks.set(golem, new GolemTask(GolemTaskType.WOODCUTTING, golem.blockPosition()));
		return golem;
	}

	private static void buildFloor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
	}

	/** A small oak: 4-block trunk with a 3x3 layer of natural leaves around the top two logs and one on top. */
	private static void buildOakTree(GameTestHelper helper) {
		for (int y = 0; y < TRUNK_HEIGHT; y++) {
			helper.setBlock(TRUNK_BASE.above(y), Blocks.OAK_LOG);
		}
		BlockState leaves = naturalLeaves();
		for (int y = TRUNK_HEIGHT - 2; y < TRUNK_HEIGHT; y++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos pos = TRUNK_BASE.offset(dx, y, dz);
					if (dx != 0 || dz != 0) {
						helper.setBlock(pos, leaves);
					}
				}
			}
		}
		helper.setBlock(TRUNK_BASE.above(TRUNK_HEIGHT), leaves);
	}

	private static BlockState naturalLeaves() {
		return Blocks.OAK_LEAVES.defaultBlockState()
			.setValue(LeavesBlock.PERSISTENT, false)
			.setValue(LeavesBlock.DISTANCE, 1);
	}
}
