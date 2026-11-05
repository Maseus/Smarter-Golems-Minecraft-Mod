package com.smartergolems.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smartergolems.SmarterGolemsConfig;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * A task assigned to a copper golem.
 *
 * @param type      what the golem should do
 * @param anchor    center of the work area: where the golem stood when the task was assigned
 * @param owner     the player who assigned the task; only they (or operators) can change it
 * @param ownerName the owner's name when the task was assigned, for display
 */
public record GolemTask(GolemTaskType type, BlockPos anchor, Optional<UUID> owner, String ownerName) {
	/** Blocks above/below the anchor that still count, so slightly uneven farms work. */
	public static final int VERTICAL_RADIUS = 2;

	public static final Codec<GolemTask> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		GolemTaskType.CODEC.fieldOf("type").forGetter(GolemTask::type),
		BlockPos.CODEC.fieldOf("anchor").forGetter(GolemTask::anchor),
		// Optional so tasks saved before owners existed still load (as unowned).
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(GolemTask::owner),
		Codec.STRING.optionalFieldOf("owner_name", "").forGetter(GolemTask::ownerName)
	).apply(instance, GolemTask::new));

	/** An unowned task (anyone may change it). */
	public GolemTask(GolemTaskType type, BlockPos anchor) {
		this(type, anchor, Optional.empty(), "");
	}

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
