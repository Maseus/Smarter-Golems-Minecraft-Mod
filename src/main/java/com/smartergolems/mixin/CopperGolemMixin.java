package com.smartergolems.mixin;

import com.smartergolems.farming.FarmingWorker;
import com.smartergolems.golem.GolemInteraction;
import com.smartergolems.golem.GolemInventory;
import com.smartergolems.golem.WorkingGolem;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.GolemTasks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin implements WorkingGolem {
	@Unique
	private final FarmingWorker smartergolems$farmingWorker = new FarmingWorker();

	@Override
	public FarmingWorker smartergolems$getFarmingWorker() {
		return smartergolems$farmingWorker;
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
			if (task.type() == GolemTaskType.FARMING) {
				smartergolems$farmingWorker.tick(level, golem, task);
				ci.cancel();
			}
		});
	}

	@Inject(method = "dropEquipment", at = @At("HEAD"))
	private void smartergolems$dropCarriedOnDeath(ServerLevel level, CallbackInfo ci) {
		GolemInventory.dropAll((CopperGolem) (Object) this, level);
	}

	@Inject(method = "turnToStatue", at = @At("HEAD"))
	private void smartergolems$dropCarriedOnStatue(ServerLevel level, CallbackInfo ci) {
		GolemInventory.dropAll((CopperGolem) (Object) this, level);
	}
}
