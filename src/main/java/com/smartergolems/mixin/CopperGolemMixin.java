package com.smartergolems.mixin;

import com.smartergolems.golem.GolemInteraction;
import com.smartergolems.golem.GolemInventory;
import com.smartergolems.golem.WorkingGolem;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import com.smartergolems.work.TaskWorker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin implements WorkingGolem {
	@Unique
	private @Nullable TaskWorker smartergolems$worker;
	@Unique
	private @Nullable GolemTaskType smartergolems$workerType;

	@Override
	public TaskWorker smartergolems$workerFor(GolemTaskType type) {
		if (smartergolems$worker == null || smartergolems$workerType != type) {
			// Clean up the old worker (open chest, navigation) even if the task was changed
			// without going through the task screen, e.g. by a command.
			if (smartergolems$worker != null && ((CopperGolem) (Object) this).level() instanceof ServerLevel level) {
				smartergolems$worker.reset(level, (CopperGolem) (Object) this);
			}
			smartergolems$worker = type.createWorker();
			smartergolems$workerType = type;
		}
		return smartergolems$worker;
	}

	@Override
	public void smartergolems$resetWorker(ServerLevel level) {
		if (smartergolems$worker != null) {
			smartergolems$worker.reset(level, (CopperGolem) (Object) this);
		}
	}

	/**
	 * Runs before vanilla's copper golem interaction. If the player is sneaking
	 * with an empty main hand, we take over; otherwise vanilla behavior is untouched.
	 */
	@Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
	private void smartergolems$onInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		CopperGolem golem = (CopperGolem) (Object) this;
		if (GolemInteraction.isTaskInteraction(player, hand)) {
			cir.setReturnValue(GolemInteraction.onTaskInteraction(golem, player));
		}
	}

	/** While the golem has a task, our worker replaces the vanilla brain (chest sorting, wandering). */
	@Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
	private void smartergolems$runTask(ServerLevel level, CallbackInfo ci) {
		CopperGolem golem = (CopperGolem) (Object) this;
		GolemTasks.get(golem).ifPresent(task -> {
			smartergolems$workerFor(task.type()).tick(level, golem, task);
			ci.cancel();
		});
	}

	@Inject(method = "dropEquipment", at = @At("HEAD"))
	private void smartergolems$dropCarriedOnDeath(ServerLevel level, CallbackInfo ci) {
		CopperGolem golem = (CopperGolem) (Object) this;
		GolemInventory.dropAll(golem, level);
		// Mobs only drop held items by chance; a working golem's tool should always come back.
		if (GolemTasks.get(golem).isPresent() && !golem.getMainHandItem().isEmpty()) {
			golem.spawnAtLocation(level, golem.getMainHandItem());
			golem.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
		}
	}

	@Inject(method = "turnToStatue", at = @At("HEAD"))
	private void smartergolems$dropCarriedOnStatue(ServerLevel level, CallbackInfo ci) {
		GolemInventory.dropAll((CopperGolem) (Object) this, level);
	}
}
