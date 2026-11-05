package com.smartergolems.test;

import com.smartergolems.golem.GolemInteraction;
import com.smartergolems.golem.GolemInventory;
import com.smartergolems.golem.GolemStatus;
import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

/** Tests for supplies (tools, bone meal), more crops, collecting, status and the owner lock. */
public class FeaturesGameTest {
	private static final BlockPos GOLEM = new BlockPos(1, 1, 1);
	private static final BlockPos CHEST = new BlockPos(0, 1, 7);

	/** Pumpkins from a stem, sugar cane (keeping the base), nether wart and sweet berries are harvested. */
	@GameTest(maxTicks = 1200, padding = TestUtil.PADDING)
	public void harvestsOtherCrops(GameTestHelper helper) {
		floor(helper);
		// Pumpkin grown from an attached stem (should be harvested) ...
		helper.setBlock(2, 0, 4, Blocks.FARMLAND);
		helper.setBlock(new BlockPos(2, 1, 4), Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState().setValue(AttachedStemBlock.FACING, Direction.EAST));
		helper.setBlock(3, 1, 4, Blocks.PUMPKIN);
		// ... and a decorative pumpkin with no stem (should be left alone).
		helper.setBlock(6, 1, 1, Blocks.PUMPKIN);
		// Sugar cane, 3 tall, next to water.
		helper.setBlock(5, 0, 5, Blocks.WATER);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(5, y, 4, Blocks.SUGAR_CANE);
		}
		// Ripe nether wart on soul sand.
		helper.setBlock(1, 0, 5, Blocks.SOUL_SAND);
		helper.setBlock(new BlockPos(1, 1, 5), Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE));
		// Ripe sweet berry bush.
		helper.setBlock(new BlockPos(6, 1, 3), Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, SweetBerryBushBlock.MAX_AGE));

		farmer(helper);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, 1, 4));
			// The stem stays (vanilla turns it back into a normal stem once its pumpkin is gone).
			helper.assertBlockPresent(Blocks.PUMPKIN_STEM, new BlockPos(2, 1, 4));
			helper.assertBlockPresent(Blocks.PUMPKIN, new BlockPos(6, 1, 1));
			helper.assertBlockPresent(Blocks.SUGAR_CANE, new BlockPos(5, 1, 4));
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(5, 2, 4));
			helper.assertBlockState(new BlockPos(1, 1, 5), s -> s.is(Blocks.NETHER_WART) && s.getValue(NetherWartBlock.AGE) == 0,
				s -> Component.literal("Expected replanted nether wart, found " + s));
			helper.assertBlockState(new BlockPos(6, 1, 3), s -> s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(SweetBerryBushBlock.AGE) == 1,
				s -> Component.literal("Expected a picked berry bush, found " + s));
		});
	}

	/**
	 * With a hoe, the golem grows its farm: it tills dirt next to existing farmland (near water)
	 * and plants it, one block per seed it carries. Dirt too far from water is left alone.
	 */
	@GameTest(maxTicks = 1200, padding = TestUtil.PADDING)
	public void tillsWithHoe(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(2, 0, 2, Blocks.WATER);
		helper.setBlock(3, 0, 2, Blocks.FARMLAND);
		helper.setBlock(new BlockPos(3, 1, 2), ((CropBlock) Blocks.WHEAT).getStateForAge(0));
		BlockPos lawn = new BlockPos(7, 0, 7); // more than 4 blocks from water
		CopperGolem golem = farmer(helper);
		golem.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HOE));
		GolemInventory.get(golem).addItem(new ItemStack(Items.WHEAT_SEEDS, 3));

		helper.succeedWhen(() -> {
			int planted = 0;
			for (int x = 0; x < 8; x++) {
				for (int z = 0; z < 8; z++) {
					BlockPos pos = new BlockPos(x, 0, z);
					if (!pos.equals(new BlockPos(3, 0, 2))
						&& helper.getLevel().getBlockState(helper.absolutePos(pos)).is(Blocks.FARMLAND)
						&& helper.getLevel().getBlockState(helper.absolutePos(pos.above())).is(Blocks.WHEAT)) {
						planted++;
					}
				}
			}
			helper.assertTrue(planted == 3, "Expected 3 newly tilled and planted blocks (one per seed), found " + planted);
			helper.assertBlockPresent(Blocks.DIRT, lawn);
			helper.assertTrue(golem.getMainHandItem().getDamageValue() == 3, "The hoe should be used once per tilled block");
		});
	}

	/** The golem fetches a missing tool and bone meal from a chest, and uses the bone meal on crops. */
	@GameTest(maxTicks = 1200, padding = TestUtil.PADDING)
	public void restocksAndUsesBoneMeal(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(CHEST, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.IRON_HOE));
		chest.setItem(1, new ItemStack(Items.BONE_MEAL, 32));
		helper.setBlock(4, 0, 4, Blocks.FARMLAND);
		BlockPos wheat = new BlockPos(4, 1, 4);
		helper.setBlock(wheat, ((CropBlock) Blocks.WHEAT).getStateForAge(0));
		CopperGolem golem = farmer(helper);

		helper.succeedWhen(() -> {
			helper.assertTrue(golem.getMainHandItem().is(Items.IRON_HOE), "Golem should hold the hoe from the chest");
			helper.assertTrue(chest.countItem(Items.IRON_HOE) == 0, "The hoe should have left the chest");
			helper.assertTrue(chest.countItem(Items.BONE_MEAL) == 16, "Golem should take 16 bone meal, chest has " + chest.countItem(Items.BONE_MEAL));
			int carried = GolemInventory.get(golem).countItem(Items.BONE_MEAL);
			helper.assertTrue(carried < 16, "Golem should have used some bone meal, still carries " + carried);
		});
	}

	/** A woodcutting golem takes an axe (but not a hoe) from its chest. */
	@GameTest(maxTicks = 600, padding = TestUtil.PADDING)
	public void woodcutterTakesAxe(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(CHEST, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.IRON_HOE));
		chest.setItem(1, new ItemStack(Items.STONE_AXE));
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		GolemTasks.set(golem, new GolemTask(GolemTaskType.WOODCUTTING, golem.blockPosition()));

		helper.succeedWhen(() -> {
			helper.assertTrue(golem.getMainHandItem().is(Items.STONE_AXE), "Golem should hold the stone axe");
			helper.assertTrue(chest.countItem(Items.IRON_HOE) == 1, "The hoe should stay in the chest");
		});
	}

	/** A collecting golem picks up dropped items in its area and puts them in a chest. */
	@GameTest(maxTicks = 1200, padding = TestUtil.PADDING)
	public void collectsDroppedItems(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.spawnItem(Items.ROTTEN_FLESH, new BlockPos(5, 1, 2));
		helper.spawnItem(Items.BONE, new BlockPos(6, 1, 5));
		helper.spawnItem(Items.STRING, new BlockPos(3, 1, 6));
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		GolemTasks.set(golem, new GolemTask(GolemTaskType.COLLECTING, golem.blockPosition()));

		helper.succeedWhen(() -> {
			ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
			for (var item : new net.minecraft.world.item.Item[] {Items.ROTTEN_FLESH, Items.BONE, Items.STRING}) {
				helper.assertTrue(chest.countItem(item) == 1, "Expected " + item + " in the chest");
			}
			AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(8, 4, 8);
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "No items should be left lying around");
		});
	}

	/**
	 * If the task changes behind the worker's back (e.g. via /data) while the golem has a chest
	 * open, the old worker still cleans up: the chest closes and the golem leaves its drop-off pose.
	 */
	@GameTest(maxTicks = 600, padding = TestUtil.PADDING)
	public void taskChangeClosesChest(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(CHEST, Blocks.CHEST);
		CopperGolem golem = farmer(helper);
		GolemInventory.get(golem).addItem(new ItemStack(Items.WHEAT, 10)); // nothing to farm, so it deposits
		ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		boolean[] switched = {false};

		helper.onEachTick(() -> {
			if (!switched[0] && golem.getState() == CopperGolemState.DROPPING_ITEM) {
				switched[0] = true;
				// Like /data: swap the task attachment directly, without going through the task screen.
				GolemTasks.set(golem, new GolemTask(GolemTaskType.WOODCUTTING, golem.blockPosition()));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(switched[0], "Golem never started depositing");
			helper.assertTrue(golem.getState() == CopperGolemState.IDLE, "Golem should leave the drop-off pose, is " + golem.getState());
			helper.assertTrue(chest.getEntitiesWithContainerOpen().isEmpty(), "Chest should be closed again");
		});
	}

	/** A working golem has a synced status (task + activity), which goes away when its task is cleared. */
	@GameTest(padding = TestUtil.PADDING)
	public void statusFollowsTask(GameTestHelper helper) {
		floor(helper);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		GolemInteraction.applyTask(golem, player, Optional.of(GolemTaskType.FARMING));

		helper.runAfterDelay(5, () -> {
			GolemStatus status = golem.getAttached(GolemStatus.ATTACHMENT);
			helper.assertTrue(status != null && status.task() == GolemTaskType.FARMING, "Expected a farming status, got " + status);
			GolemInteraction.applyTask(golem, player, Optional.empty());
			helper.assertTrue(!golem.hasAttached(GolemStatus.ATTACHMENT), "Status should be cleared with the task");
			helper.succeed();
		});
	}

	/** Only the player who assigned the task can change it or take the golem's items. */
	@GameTest(padding = TestUtil.PADDING)
	public void ownerLock(GameTestHelper helper) {
		floor(helper);
		ServerPlayer owner = helper.makeMockServerPlayerInLevel();
		ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);

		GolemInteraction.applyTask(golem, owner, Optional.of(GolemTaskType.FARMING));
		GolemInventory.get(golem).addItem(new ItemStack(Items.WHEAT, 5));

		helper.assertTrue(!GolemInteraction.canEdit(golem, stranger), "A stranger should not be able to edit (are mock players operators?)");
		GolemInteraction.applyTask(golem, stranger, Optional.of(GolemTaskType.WOODCUTTING));
		GolemInteraction.applyTask(golem, stranger, Optional.empty());
		GolemInteraction.takeItems(golem, stranger);
		helper.assertTrue(GolemTasks.get(golem).map(t -> t.type() == GolemTaskType.FARMING).orElse(false), "Stranger must not change the task");
		helper.assertTrue(GolemInventory.get(golem).countItem(Items.WHEAT) == 5, "Stranger must not take items");
		helper.assertTrue(stranger.getInventory().countItem(Items.WHEAT) == 0, "Stranger got the wheat");

		GolemInteraction.takeItems(golem, owner);
		helper.assertTrue(owner.getInventory().countItem(Items.WHEAT) == 5, "Owner should get the wheat");
		helper.assertTrue(GolemInventory.isEmpty(golem), "Bag should be empty after taking");
		GolemInteraction.applyTask(golem, owner, Optional.of(GolemTaskType.WOODCUTTING));
		helper.assertTrue(GolemTasks.get(golem).map(t -> t.type() == GolemTaskType.WOODCUTTING).orElse(false), "Owner can change the task");
		helper.succeed();
	}

	private static CopperGolem farmer(GameTestHelper helper) {
		CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, GOLEM);
		GolemTasks.set(golem, new GolemTask(GolemTaskType.FARMING, golem.blockPosition()));
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
