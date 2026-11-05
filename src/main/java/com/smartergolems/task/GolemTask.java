package com.smartergolems.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smartergolems.SmarterGolemsConfig;
import net.minecraft.core.BlockPos;

/**
 * A task assigned to a copper golem.
 *
 * @param type   what the golem should do
 * @param anchor center of the work area: where the golem stood when the task was assigned
 */
public record GolemTask(GolemTaskType type, BlockPos anchor) {
	/** Blocks above/below the anchor that still count, so slightly uneven farms work. */
	public static final int VERTICAL_RADIUS = 2;

	public static final Codec<GolemTask> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		GolemTaskType.CODEC.fieldOf("type").forGetter(GolemTask::type),
		BlockPos.CODEC.fieldOf("anchor").forGetter(GolemTask::anchor)
	).apply(instance, GolemTask::new));

	/** Blocks from the anchor to the edge of the area horizontally (from the config; 7 gives 15x15). */
	public static int horizontalRadius() {
		return SmarterGolemsConfig.get().farmRadius;
	}

	/** Width of the square work area in blocks. */
	public static int areaSize() {
		return horizontalRadius() * 2 + 1;
	}

	/** Every block position in the work area. */
	public Iterable<BlockPos> areaPositions() {
		int r = horizontalRadius();
		return BlockPos.betweenClosed(
			anchor.offset(-r, -VERTICAL_RADIUS, -r),
			anchor.offset(r, VERTICAL_RADIUS, r)
		);
	}
}
