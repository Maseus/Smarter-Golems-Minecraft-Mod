package com.smartergolems.test;

import com.smartergolems.golem.GolemInteraction;
import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import com.smartergolems.task.TaughtBlock;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Tests for the custom task: teaching by example and mining the taught blocks. */
public class CustomGameTest {
	private static final BlockPos GOLEM = new BlockPos(1, 1, 1);
	private static final BlockPos CHEST = new BlockPos(0, 1, 7);

	/**
	 * The golem takes a pickaxe (not the shovel) from its chest, mines only the taught spots that
	 * still hold the taught block, and deposits the cobblestone.
	 */
	@GameTest(maxTicks = 1200, padding = TestUtil.PADDING)
	public void minesTaughtBlocks(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(CHEST, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.STONE_SHOVEL));
		chest.setItem(1, new ItemStack(Items.STONE_PICKAXE));
		BlockPos first = new BlockPos(4, 1, 4);
		BlockPos second = new BlockPos(5, 1, 4);
		BlockPos changed = new BlockPos(6, 1, 6);   // taught as cobblestone, now stone: left alone
		BlockPos untaught = new BlockPos(4, 1, 2);
		helper.setBlock(first, Blocks.COBBLESTONE);
		helper.setBlock(second, Blocks.COBBLESTONE);
		helper.setBlock(changed, Blocks.STONE);
		helper.setBlock(untaught, Blocks.COBBLESTONE);
		CopperGolem golem = custom(helper, taught(helper, Blocks.COBBLESTONE, first, second, changed));

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, first);
			helper.assertBlockPresent(Blocks.AIR, second);
			helper.assertBlockPresent(Blocks.STONE, changed);
			helper.assertBlockPresent(Blocks.COBBLESTONE, untaught);
			helper.assertTrue(golem.getMainHandItem().is(Items.STONE_PICKAXE), "Golem should hold the pickaxe, holds " + golem.getMainHandItem());
			helper.assertTrue(golem.getMainHandItem().getDamageValue() == 2, "The pickaxe should be used once per block");
			helper.assertTrue(chest.countItem(Items.STONE_SHOVEL) == 1, "The shovel should stay in the chest");
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 2, "Expected 2 cobblestone in the chest, found " + chest.countItem(Items.COBBLESTONE));
		});
	}

	/** Without a pickaxe, stone-type blocks are left alone (they'd drop nothing); dirt is dug by hand. */
	@GameTest(maxTicks = 800, padding = TestUtil.PADDING)
	public void needsRightToolForDrops(GameTestHelper helper) {
		floor(helper);
		BlockPos cobble = new BlockPos(4, 1, 4);
		BlockPos dirt = new BlockPos(5, 1, 2);
		helper.setBlock(cobble, Blocks.COBBLESTONE);
		helper.setBlock(dirt, Blocks.DIRT);
		List<TaughtBlock> taught = List.of(
			new TaughtBlock(helper.absolutePos(cobble), Blocks.COBBLESTONE),
			new TaughtBlock(helper.absolutePos(dirt), Blocks.DIRT));
		CopperGolem golem = custom(helper, taught);

		helper.runAtTickTime(600, () -> {
			helper.assertBlockPresent(Blocks.COBBLESTONE, cobble);
			helper.assertBlockPresent(Blocks.AIR, dirt);
			helper.assertTrue(GolemInventory.get(golem).countItem(Items.DIRT) == 1, "Golem should carry the dirt");
			helper.succeed();
		});
	}

	/**
	 * Teaching: blocks the player breaks near the golem are recorded (not chests, not blocks
	 * outside the area); sneak + right-click finishes and gives the golem the custom task.
	 */
	@GameTest(padding = TestUtil.PADDING)
	public void teachingRecordsBrokenBlocks(GameTestHelper helper) {
		floor(helper);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		BlockPos cobble = new BlockPos(3, 1, 3);
		BlockPos chest = new BlockPos(5, 1, 5);
		BlockPos far = new BlockPos(1 + 9, 1, 1); // outside the 15x15 area
		helper.setBlock(cobble, Blocks.COBBLESTONE);
		helper.setBlock(chest, Blocks.CHEST);
		helper.setBlock(far, Blocks.STONE);

		// Finishing without breaking anything leaves the golem as it was.
		GolemInteraction.applyTask(golem, player, Optional.of(GolemTaskType.CUSTOM));
		player.setShiftKeyDown(true);
		helper.assertTrue(GolemInteraction.tryFinishTeaching(golem, player, InteractionHand.MAIN_HAND), "Sneak + right-click should finish teaching");
		helper.assertTrue(GolemTasks.get(golem).isEmpty(), "Nothing taught: no task expected");

		GolemInteraction.applyTask(golem, player, Optional.of(GolemTaskType.CUSTOM));
		helper.assertTrue(GolemTasks.get(golem).isEmpty(), "The task is only set when teaching finishes");
		for (BlockPos pos : new BlockPos[] {cobble, chest, far}) {
			player.gameMode.destroyBlock(helper.absolutePos(pos));
		}
		helper.assertBlockPresent(Blocks.AIR, cobble);
		// Holding a pickaxe doesn't get in the way of finishing.
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
		helper.assertTrue(GolemInteraction.tryFinishTeaching(golem, player, InteractionHand.MAIN_HAND), "Sneak + right-click should finish teaching");

		GolemTask task = GolemTasks.get(golem).orElseThrow();
		helper.assertValueEqual(task.type(), GolemTaskType.CUSTOM, "task type");
		helper.assertValueEqual(task.taught(), List.of(new TaughtBlock(helper.absolutePos(cobble), Blocks.COBBLESTONE)), "taught blocks");
		helper.assertValueEqual(task.anchor(), golem.blockPosition(), "anchor");
		helper.assertTrue(!GolemInteraction.tryFinishTeaching(golem, player, InteractionHand.MAIN_HAND), "Teaching should be over");
		helper.succeed();
	}

	private static List<TaughtBlock> taught(GameTestHelper helper, Block block, BlockPos... positions) {
		return java.util.Arrays.stream(positions).map(pos -> new TaughtBlock(helper.absolutePos(pos), block)).toList();
	}

	private static CopperGolem custom(GameTestHelper helper, List<TaughtBlock> taught) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		GolemTasks.set(golem, new GolemTask(GolemTaskType.CUSTOM, golem.blockPosition(), Optional.empty(), "", taught));
		return golem;
	}

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
	}
}
