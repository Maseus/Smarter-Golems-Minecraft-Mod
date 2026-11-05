package com.smartergolems.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * A task assigned to a copper golem.
 *
 * @param type   what the golem should do
 * @param anchor center of the work area: where the golem stood when the task was assigned
 */
public record GolemTask(GolemTaskType type, BlockPos anchor) {
	/** Blocks from the anchor to the edge of the area horizontally: 4 gives a 9x9 area. */
	public static final int HORIZONTAL_RADIUS = 4;
	/** Blocks above/below the anchor that still count, so slightly uneven farms work. */
	public static final int VERTICAL_RADIUS = 2;

	public static final Codec<GolemTask> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		GolemTaskType.CODEC.fieldOf("type").forGetter(GolemTask::type),
		BlockPos.CODEC.fieldOf("anchor").forGetter(GolemTask::anchor)
	).apply(instance, GolemTask::new));

	public boolean isInArea(BlockPos pos) {
		return Math.abs(pos.getX() - anchor.getX()) <= HORIZONTAL_RADIUS
			&& Math.abs(pos.getZ() - anchor.getZ()) <= HORIZONTAL_RADIUS
			&& Math.abs(pos.getY() - anchor.getY()) <= VERTICAL_RADIUS;
	}

	/** Every block position in the work area. */
	public Iterable<BlockPos> areaPositions() {
		return BlockPos.betweenClosed(
			anchor.offset(-HORIZONTAL_RADIUS, -VERTICAL_RADIUS, -HORIZONTAL_RADIUS),
			anchor.offset(HORIZONTAL_RADIUS, VERTICAL_RADIUS, HORIZONTAL_RADIUS)
		);
	}
}
