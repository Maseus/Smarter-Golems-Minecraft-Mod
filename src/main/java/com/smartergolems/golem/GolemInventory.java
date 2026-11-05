package com.smartergolems.golem;

import com.mojang.serialization.Codec;
import com.smartergolems.SmarterGolems;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;

/**
 * A small bag of items a working golem carries (e.g. harvested crops), saved with the golem.
 * Kept separate from the golem's hand so vanilla hand behavior is unaffected.
 */
public final class GolemInventory {
	public static final int SIZE = 9;

	private static final Codec<SimpleContainer> CODEC = ItemStack.CODEC.listOf().xmap(
		stacks -> {
			SimpleContainer container = new SimpleContainer(SIZE);
			stacks.forEach(container::addItem);
			return container;
		},
		container -> container.getItems().stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList()
	);

	public static final AttachmentType<SimpleContainer> CARRIED = AttachmentRegistry.create(
		SmarterGolems.id("carried"),
		builder -> builder.persistent(CODEC).initializer(() -> new SimpleContainer(SIZE))
	);

	private GolemInventory() {}

	/** Forces class loading so the attachment type registers during mod init. */
	public static void init() {}

	public static SimpleContainer get(CopperGolem golem) {
		return golem.getAttachedOrCreate(CARRIED);
	}

	/** Adds items to the bag; anything that doesn't fit is dropped on the ground. */
	public static void addOrDrop(CopperGolem golem, ServerLevel level, ItemStack stack) {
		ItemStack remainder = get(golem).addItem(stack);
		if (!remainder.isEmpty()) {
			golem.spawnAtLocation(level, remainder);
		}
	}

	public static int usedSlots(CopperGolem golem) {
		if (!golem.hasAttached(CARRIED)) {
			return 0;
		}
		int used = 0;
		for (ItemStack stack : get(golem).getItems()) {
			if (!stack.isEmpty()) {
				used++;
			}
		}
		return used;
	}

	public static boolean isEmpty(CopperGolem golem) {
		return usedSlots(golem) == 0;
	}

	/** Drops everything the golem carries and empties the bag. */
	public static void dropAll(CopperGolem golem, ServerLevel level) {
		if (!golem.hasAttached(CARRIED)) {
			return;
		}
		for (ItemStack stack : get(golem).removeAllItems()) {
			if (!stack.isEmpty()) {
				golem.spawnAtLocation(level, stack);
			}
		}
		golem.removeAttached(CARRIED);
	}
}
