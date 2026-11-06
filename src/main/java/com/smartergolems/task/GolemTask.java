package com.smartergolems.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smartergolems.SmarterGolemsConfig;
import java.util.List;
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
 * @param taught    blocks the player showed the golem to mine (custom task only; empty otherwise)
 * @param uses      right-clicks the player showed the golem (custom task only; empty otherwise)
 */
public record GolemTask(GolemTaskType type, BlockPos anchor, Optional<UUID> owner, String ownerName,
                        List<TaughtBlock> taught, List<TaughtUse> uses) {
	/** Blocks above/below the anchor that still count, so slightly uneven farms work. */
	public static final int VERTICAL_RADIUS = 2;

	public static final Codec<GolemTask> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		GolemTaskType.CODEC.fieldOf("type").forGetter(GolemTask::type),
		BlockPos.CODEC.fieldOf("anchor").forGetter(GolemTask::anchor),
		// Optional so tasks saved before owners existed still load (as unowned).
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(GolemTask::owner),
		Codec.STRING.optionalFieldOf("owner_name", "").forGetter(GolemTask::ownerName),
		TaughtBlock.CODEC.listOf().optionalFieldOf("taught", List.of()).forGetter(GolemTask::taught),
		TaughtUse.CODEC.listOf().optionalFieldOf("uses", List.of()).forGetter(GolemTask::uses)
	).apply(instance, GolemTask::new));

	public GolemTask {
		taught = List.copyOf(taught);
		uses = List.copyOf(uses);
	}

	/** A custom task that only mines. */
	public GolemTask(GolemTaskType type, BlockPos anchor, Optional<UUID> owner, String ownerName, List<TaughtBlock> taught) {
		this(type, anchor, owner, ownerName, taught, List.of());
	}

	/** A task without anything taught (every task except custom). */
	public GolemTask(GolemTaskType type, BlockPos anchor, Optional<UUID> owner, String ownerName) {
		this(type, anchor, owner, ownerName, List.of());
	}

	/** Every spot a custom task works on (to mark them in the task screen). */
	public List<BlockPos> taughtPositions() {
		return java.util.stream.Stream.concat(taught.stream().map(TaughtBlock::pos), uses.stream().map(TaughtUse::pos))
			.distinct().toList();
	}

	/** An unowned task (anyone may change it). */
	public GolemTask(GolemTaskType type, BlockPos anchor) {
		this(type, anchor, Optional.empty(), "");
	}

	/** Blocks from the anchor to the edge of the area horizontally for this task (from the config). */
	public int radius() {
		return radiusFor(type);
	}

	/** Farming uses the water range (9x9 by default); other tasks the general area (15x15). */
	public static int radiusFor(GolemTaskType type) {
		SmarterGolemsConfig config = SmarterGolemsConfig.get();
		return type == GolemTaskType.FARMING ? config.farmingRadius : config.areaRadius;
	}

	/** Width of the square work area in blocks. */
	public int areaSize() {
		return radius() * 2 + 1;
	}

	/** Every block position in the work area. */
	public Iterable<BlockPos> areaPositions() {
		int r = radius();
		return BlockPos.betweenClosed(
			anchor.offset(-r, -VERTICAL_RADIUS, -r),
			anchor.offset(r, VERTICAL_RADIUS, r)
		);
	}
}
