package com.smartergolems.client.mixin;

import com.smartergolems.client.GolemStatusText;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows the name tag of a working golem the player is looking at. Mobs normally only show
 * a name tag when they have a custom name, and that rule lives in MobRenderer's override.
 */
@Mixin(MobRenderer.class)
public abstract class MobRendererMixin {
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Mob;D)Z", at = @At("RETURN"), cancellable = true)
	private void smartergolems$showStatus(Mob entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && GolemStatusText.shouldShow(entity)) {
			cir.setReturnValue(true);
		}
	}
}
