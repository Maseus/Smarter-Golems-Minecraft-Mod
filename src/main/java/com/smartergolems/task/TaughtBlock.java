package com.smartergolems.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * One block a player showed a golem during teaching: whenever {@code block} is at {@code pos},
 * a golem with the custom task mines it (e.g. the cobblestone a generator keeps making).
 */
public record TaughtBlock(BlockPos pos, Block block) {
	public static final Codec<TaughtBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.fieldOf("pos").forGetter(TaughtBlock::pos),
		BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(TaughtBlock::block)
	).apply(instance, TaughtBlock::new));
}
