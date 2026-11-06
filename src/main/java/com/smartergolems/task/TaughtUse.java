package com.smartergolems.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A right-click a player showed a golem during teaching: whenever the block at {@code pos} is
 * exactly {@code before} again, the golem does the same thing with the same item. For example:
 * place dirt where there is air, use a water bottle on dirt (mud), fill a bucket at a water source.
 *
 * <p>The golem replays it as the player did it, from where they stood and looking where they
 * looked, so placement facing and aiming at fluids work the same way.
 *
 * @param pos     the block that changed (or, if only the item changed, the block clicked)
 * @param before  that block's state before the click: the rule's condition
 * @param item    one of the item used (empty for an empty hand)
 * @param onBlock true for a click on a block face (placing, using an item on a block), false for
 *                using the item in the air (buckets and bottles at fluids)
 * @param clicked the block that was clicked
 * @param face    the face that was clicked
 * @param hit     the exact point that was clicked
 * @param from    where the player stood
 * @param yRot    the player's horizontal look angle
 * @param xRot    the player's vertical look angle
 */
public record TaughtUse(BlockPos pos, BlockState before, ItemStack item, boolean onBlock,
                        BlockPos clicked, Direction face, Vec3 hit, Vec3 from, float yRot, float xRot) {
	public static final Codec<TaughtUse> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.fieldOf("pos").forGetter(TaughtUse::pos),
		BlockState.CODEC.fieldOf("before").forGetter(TaughtUse::before),
		ItemStack.OPTIONAL_CODEC.optionalFieldOf("item", ItemStack.EMPTY).forGetter(TaughtUse::item),
		Codec.BOOL.fieldOf("on_block").forGetter(TaughtUse::onBlock),
		BlockPos.CODEC.fieldOf("clicked").forGetter(TaughtUse::clicked),
		Direction.CODEC.fieldOf("face").forGetter(TaughtUse::face),
		Vec3.CODEC.fieldOf("hit").forGetter(TaughtUse::hit),
		Vec3.CODEC.fieldOf("from").forGetter(TaughtUse::from),
		Codec.FLOAT.fieldOf("y_rot").forGetter(TaughtUse::yRot),
		Codec.FLOAT.fieldOf("x_rot").forGetter(TaughtUse::xRot)
	).apply(instance, TaughtUse::new));

	public TaughtUse {
		item = item.copyWithCount(item.isEmpty() ? 0 : 1);
	}

	public BlockHitResult hitResult() {
		return new BlockHitResult(hit, face, clicked, false);
	}

	/** Same spot and condition: teaching it again replaces the old one. */
	public boolean sameRule(TaughtUse other) {
		return pos.equals(other.pos) && before.equals(other.before);
	}

	/**
	 * Records hold an ItemStack, whose equals is identity; compare it by item and components so
	 * tasks compare (and save/load round-trip) as expected.
	 */
	@Override
	public boolean equals(Object o) {
		return o instanceof TaughtUse other && pos.equals(other.pos) && before.equals(other.before)
			&& ItemStack.matches(item, other.item) && onBlock == other.onBlock && clicked.equals(other.clicked)
			&& face == other.face && hit.equals(other.hit) && from.equals(other.from)
			&& Float.compare(yRot, other.yRot) == 0 && Float.compare(xRot, other.xRot) == 0;
	}

	@Override
	public int hashCode() {
		return pos.hashCode() * 31 + before.hashCode();
	}
}
