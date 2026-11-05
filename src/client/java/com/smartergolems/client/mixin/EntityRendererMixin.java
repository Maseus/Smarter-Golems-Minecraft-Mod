package com.smartergolems.client.mixin;

import com.smartergolems.client.GolemStatusText;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces a working golem's name tag text with its status (see {@link MobRendererMixin} for when it shows). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void smartergolems$statusText(Entity entity, CallbackInfoReturnable<Component> cir) {
		Component status = GolemStatusText.of(entity);
		if (status != null) {
			cir.setReturnValue(status);
		}
	}
}
