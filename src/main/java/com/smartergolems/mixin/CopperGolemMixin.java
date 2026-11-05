package com.smartergolems.mixin;

import com.smartergolems.golem.GolemInteraction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin {
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
}
