package com.smartergolems.work;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.task.GolemTask;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Collecting: picks up dropped items lying in the work area (e.g. under a mob farm) and
 * puts them into a chest.
 */
public class CollectingWorker extends TaskWorker {
	private static final double ITEM_REACH = 1.5;
	/** Items within this distance of the golem are picked up together on arrival. */
	private static final double PICKUP_RADIUS = 1.5;

	@Override
	protected Optional<Job> findWork(ServerLevel level, CopperGolem golem, GolemTask task) {
		SimpleContainer bag = GolemInventory.get(golem);
		return itemsInArea(level, task).stream()
			.filter(item -> !item.hasPickUpDelay() && bag.canAddItem(item.getItem()) && !isSkipped(item.blockPosition()))
			.min(Comparator.comparingDouble(golem::distanceToSqr))
			.map(item -> Job.instant(Activity.COLLECTING, item.blockPosition(), ITEM_REACH, () -> pickUpNearby(level, golem)));
	}

	private static List<ItemEntity> itemsInArea(ServerLevel level, GolemTask task) {
		int r = task.radius();
		AABB area = new AABB(task.anchor()).inflate(r, GolemTask.VERTICAL_RADIUS, r);
		return level.getEntitiesOfClass(ItemEntity.class, area, ItemEntity::isAlive);
	}

	private static void pickUpNearby(ServerLevel level, CopperGolem golem) {
		SimpleContainer bag = GolemInventory.get(golem);
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, golem.getBoundingBox().inflate(PICKUP_RADIUS),
			e -> e.isAlive() && !e.hasPickUpDelay())) {
			ItemStack stack = item.getItem();
			int before = stack.getCount();
			ItemStack remainder = bag.addItem(stack.copy());
			int taken = before - remainder.getCount();
			if (taken > 0) {
				golem.take(item, taken); // the vanilla pickup animation and sound
				if (remainder.isEmpty()) {
					item.discard();
				} else {
					item.setItem(remainder);
				}
			}
		}
	}
}
