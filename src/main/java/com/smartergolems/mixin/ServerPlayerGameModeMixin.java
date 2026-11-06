package com.smartergolems.mixin;

import com.smartergolems.golem.Teaching;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets teaching see a player's right-clicks: the world just before and just after the server
 * handles a click on a block ({@code useItemOn}) or a use in the air ({@code useItem}).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void smartergolems$beforeUseOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
	                                       BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Teaching.beforeUse(player, hand, hit);
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void smartergolems$afterUseOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
	                                      BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Teaching.afterUse(player);
	}

	@Inject(method = "useItem", at = @At("HEAD"))
	private void smartergolems$beforeUse(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
	                                     CallbackInfoReturnable<InteractionResult> cir) {
		Teaching.beforeUse(player, hand, null);
	}

	@Inject(method = "useItem", at = @At("RETURN"))
	private void smartergolems$afterUse(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
	                                    CallbackInfoReturnable<InteractionResult> cir) {
		Teaching.afterUse(player);
	}
}
