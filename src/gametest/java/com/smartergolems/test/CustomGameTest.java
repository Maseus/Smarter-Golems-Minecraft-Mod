package com.smartergolems.test;

import com.smartergolems.golem.GolemInteraction;
import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import com.smartergolems.task.TaughtBlock;
import com.smartergolems.task.TaughtUse;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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

	/**
	 * Teaching right-clicks: placing a block (the spot in front of the clicked face changes), using
	 * a water bottle on dirt (the clicked block changes) and filling a bucket at a water source
	 * (a use in the air, aimed by where the player looks). Opening a chest changes nothing and
	 * isn't recorded.
	 */
	@GameTest(padding = TestUtil.PADDING)
	public void teachingRecordsRightClicks(GameTestHelper helper) {
		floor(helper);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		BlockPos support = new BlockPos(3, 1, 3);
		BlockPos spot = support.above();
		BlockPos water = new BlockPos(5, 0, 5);
		BlockPos chest = new BlockPos(6, 1, 1);
		helper.setBlock(support, Blocks.STONE);
		helper.setBlock(water, Blocks.WATER);
		helper.setBlock(chest, Blocks.CHEST);
		GolemInteraction.applyTask(golem, player, Optional.of(GolemTaskType.CUSTOM));

		// Place dirt on top of the stone.
		clickTop(helper, player, support, new ItemStack(Items.DIRT));
		helper.assertBlockPresent(Blocks.DIRT, spot);
		// Water bottle on the dirt: mud.
		clickTop(helper, player, spot, waterBottle());
		helper.assertBlockPresent(Blocks.MUD, spot);
		// Open the chest: nothing changes.
		clickTop(helper, player, chest, ItemStack.EMPTY);
		// Bucket at the water, looking straight down at it.
		Vec3 above = Vec3.atBottomCenterOf(helper.absolutePos(water.above()));
		player.snapTo(above.x, above.y, above.z, 0.0F, 90.0F);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
		player.gameMode.useItem(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
		helper.assertBlockPresent(Blocks.AIR, water);

		player.setShiftKeyDown(true);
		helper.assertTrue(GolemInteraction.tryFinishTeaching(golem, player, InteractionHand.MAIN_HAND), "Sneak + right-click should finish teaching");
		List<TaughtUse> uses = GolemTasks.get(golem).orElseThrow().uses();
		helper.assertValueEqual(uses.size(), 3, "taught right-clicks");
		assertUse(helper, uses.get(0), spot, Blocks.AIR.defaultBlockState(), Items.DIRT, true);
		assertUse(helper, uses.get(1), spot, Blocks.DIRT.defaultBlockState(), Items.POTION, true);
		helper.assertTrue(PotionContents.createItemStack(Items.POTION, Potions.WATER).getComponents().equals(uses.get(1).item().getComponents()),
			"The water bottle should be remembered as a water bottle, not just any potion");
		assertUse(helper, uses.get(2), water, Blocks.WATER.defaultBlockState(), Items.BUCKET, false);
		helper.succeed();
	}

	/**
	 * A whole clay-farm cycle: mine the clay, place dirt from the chest, make it mud with a water
	 * bottle from the chest, then wait (the empty bottle and the clay go into the chest).
	 */
	@GameTest(maxTicks = 1200, padding = TestUtil.PADDING)
	public void clayFarmCycle(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(CHEST, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.DIRT, 4));
		chest.setItem(1, waterBottle());
		chest.setItem(2, waterBottle());
		BlockPos support = new BlockPos(4, 1, 4);
		BlockPos spot = support.above();
		helper.setBlock(support, Blocks.STONE);
		helper.setBlock(spot, Blocks.CLAY);
		BlockPos absSupport = helper.absolutePos(support);
		BlockPos absSpot = helper.absolutePos(spot);
		Vec3 stand = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 1, 2)));
		List<TaughtBlock> mine = List.of(new TaughtBlock(absSpot, Blocks.CLAY));
		List<TaughtUse> uses = List.of(
			new TaughtUse(absSpot, Blocks.AIR.defaultBlockState(), new ItemStack(Items.DIRT), true,
				absSupport, Direction.UP, Vec3.atCenterOf(absSupport).add(0, 0.5, 0), stand, 0.0F, 45.0F),
			new TaughtUse(absSpot, Blocks.DIRT.defaultBlockState(), waterBottle(), true,
				absSpot, Direction.UP, Vec3.atCenterOf(absSpot).add(0, 0.5, 0), stand, 0.0F, 45.0F));
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		GolemTasks.set(golem, new GolemTask(GolemTaskType.CUSTOM, golem.blockPosition(), Optional.empty(), "", mine, uses));

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.MUD, spot);
			helper.assertTrue(chest.countItem(Items.CLAY_BALL) == 4, "Expected 4 clay balls in the chest, found " + chest.countItem(Items.CLAY_BALL));
			helper.assertTrue(chest.countItem(Items.GLASS_BOTTLE) == 1, "The empty bottle should be in the chest");
			helper.assertTrue(GolemInventory.get(golem).countItem(Items.DIRT) == 3, "Golem should keep the rest of the dirt");
			helper.assertTrue(GolemInventory.get(golem).countItem(Items.POTION) == 1, "Golem should keep the other water bottle");
		});
	}

	/** A use in the air is aimed like the player aimed it: fill a bucket from the chest at a water source. */
	@GameTest(maxTicks = 800, padding = TestUtil.PADDING)
	public void fillsBucketAtWater(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(CHEST, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.BUCKET));
		BlockPos water = new BlockPos(4, 0, 4);
		helper.setBlock(water, Blocks.WATER);
		BlockPos abs = helper.absolutePos(water);
		TaughtUse fill = new TaughtUse(abs, Blocks.WATER.defaultBlockState(), new ItemStack(Items.BUCKET), false,
			abs, Direction.UP, Vec3.atCenterOf(abs), Vec3.atBottomCenterOf(abs.above()), 0.0F, 90.0F);
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		GolemTasks.set(golem, new GolemTask(GolemTaskType.CUSTOM, golem.blockPosition(), Optional.empty(), "", List.of(), List.of(fill)));

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.WATER, water);
			helper.assertTrue(chest.countItem(Items.WATER_BUCKET) == 1, "The water bucket should end up in the chest");
		});
	}

	private static void clickTop(GameTestHelper helper, ServerPlayer player, BlockPos pos, ItemStack item) {
		BlockPos abs = helper.absolutePos(pos);
		player.setItemInHand(InteractionHand.MAIN_HAND, item);
		player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(abs).add(0, 0.5, 0), Direction.UP, abs, false));
	}

	private static void assertUse(GameTestHelper helper, TaughtUse use, BlockPos pos, BlockState before, net.minecraft.world.item.Item item, boolean onBlock) {
		helper.assertValueEqual(use.pos(), helper.absolutePos(pos), "taught spot");
		helper.assertValueEqual(use.before(), before, "condition at " + pos);
		helper.assertTrue(use.item().is(item), "Expected " + item + ", taught " + use.item());
		helper.assertValueEqual(use.onBlock(), onBlock, "click on a block");
	}

	private static ItemStack waterBottle() {
		return PotionContents.createItemStack(Items.POTION, Potions.WATER);
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
