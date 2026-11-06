package com.smartergolems.golem;

import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.TaughtBlock;
import com.smartergolems.task.TaughtUse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Teaching a golem its custom task by example. After the player picks "Custom" in the task
 * screen, what they do near the golem is recorded: blocks they break, and right-clicks that
 * change a block (placing a block, a water bottle on dirt, a bucket at a water source...).
 * Sneak + right-clicking the golem finishes and gives it the task. Sessions live only in
 * memory and end after a while without any teaching.
 */
public final class Teaching {
	/** Most things one golem can be taught (blocks to mine plus right-clicks). */
	public static final int MAX_STEPS = 16;
	/** Taught spots may be this many blocks above or below where the golem stood. */
	public static final int MAX_HEIGHT = 4;
	/** Teaching ends after this long without anything new being taught. */
	private static final int TIMEOUT_TICKS = 5 * 60 * 20;
	private static final int MARKER_INTERVAL = 10;
	private static final int REMINDER_INTERVAL = 60;
	private static final DustParticleOptions MARKER = new DustParticleOptions(0xE07B39, 1.0F);

	/** A player teaching one golem: where its area is centered and what they've shown it so far. */
	private static final class Session {
		final UUID golem;
		final ResourceKey<Level> dimension;
		final BlockPos anchor;
		long lastActivity;
		final Map<BlockPos, TaughtBlock> blocks = new LinkedHashMap<>();
		final List<TaughtUse> uses = new ArrayList<>();

		Session(UUID golem, ResourceKey<Level> dimension, BlockPos anchor, long now) {
			this.golem = golem;
			this.dimension = dimension;
			this.anchor = anchor;
			this.lastActivity = now;
		}

		int steps() {
			return blocks.size() + uses.size();
		}
	}

	/** A block the player's right-click might change, with its state before the click. */
	private record Candidate(BlockPos pos, BlockState before, BlockHitResult hit) {}

	/** What the world looked like just before a teaching player's right-click. */
	private record PendingUse(UUID player, InteractionHand hand, ItemStack itemBefore, boolean onBlock,
	                          List<Candidate> candidates, Vec3 from, float yRot, float xRot) {}

	/** Teaching sessions by player. */
	private static final Map<UUID, Session> SESSIONS = new HashMap<>();
	/** Set just before a teaching player's right-click is handled, checked right after. */
	private static @Nullable PendingUse pending;

	private Teaching() {}

	public static void init() {
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
				onBlockBroken(serverPlayer, serverLevel, pos, state, blockEntity != null);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(Teaching::tick);
		// Sessions are kept in a static map: don't carry them into the next world in singleplayer.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			SESSIONS.clear();
			pending = null;
		});
	}

	/** Starts (or restarts) teaching this golem; its area is centered where it stands now. */
	public static void start(CopperGolem golem, ServerPlayer player) {
		ServerLevel level = player.level();
		SESSIONS.put(player.getUUID(), new Session(golem.getUUID(), level.dimension(), golem.blockPosition(), level.getGameTime()));
		player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_start"));
	}

	public static boolean isTeaching(ServerPlayer player, CopperGolem golem) {
		Session session = SESSIONS.get(player.getUUID());
		return session != null && session.golem.equals(golem.getUUID());
	}

	/** Ends teaching and, if anything was taught, gives the golem its custom task. */
	public static void finish(CopperGolem golem, ServerPlayer player) {
		Session session = SESSIONS.remove(player.getUUID());
		if (session == null) {
			return;
		}
		if (session.steps() == 0) {
			player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_nothing"));
			return;
		}
		GolemInteraction.applyCustomTask(golem, player, session.anchor,
			new ArrayList<>(session.blocks.values()), new ArrayList<>(session.uses));
	}

	// ---- Recording ----

	private static void onBlockBroken(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, boolean hadBlockEntity) {
		Session session = activeSession(player, level);
		if (session == null || !checkInArea(player, session, pos)) {
			return;
		}
		// The block is gone by now, so check what it was (the state from before it broke).
		if (hadBlockEntity || state.isAir() || !state.getFluidState().isEmpty() || state.getBlock().defaultDestroyTime() < 0) {
			player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_not_allowed"));
			return;
		}
		if (!session.blocks.containsKey(pos) && !checkRoom(player, session)) {
			return;
		}
		session.blocks.put(pos.immutable(), new TaughtBlock(pos.immutable(), state.getBlock()));
		taught(player, session);
	}

	/**
	 * Just before the server handles a right-click (called from a mixin). For a click on a block,
	 * the clicked block and the one in front of the clicked face might change. For a use in the air
	 * (buckets, bottles), whatever the player is looking at, with and without fluids.
	 */
	public static void beforeUse(ServerPlayer player, InteractionHand hand, @Nullable BlockHitResult blockHit) {
		pending = null;
		ServerLevel level = player.level();
		if (activeSession(player, level) == null) {
			return;
		}
		List<BlockHitResult> hits = new ArrayList<>();
		if (blockHit != null) {
			hits.add(blockHit);
		} else {
			Vec3 eye = player.getEyePosition();
			Vec3 to = eye.add(Player.calculateViewVector(player.getXRot(), player.getYRot()).scale(player.blockInteractionRange()));
			for (ClipContext.Fluid fluid : new ClipContext.Fluid[] {ClipContext.Fluid.SOURCE_ONLY, ClipContext.Fluid.NONE}) {
				BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, fluid, player));
				if (hit.getType() == HitResult.Type.BLOCK) {
					hits.add(hit);
				}
			}
		}
		List<Candidate> candidates = new ArrayList<>();
		for (BlockHitResult hit : hits) {
			for (BlockPos pos : List.of(hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()))) {
				if (candidates.stream().noneMatch(c -> c.pos().equals(pos))) {
					candidates.add(new Candidate(pos.immutable(), level.getBlockState(pos), hit));
				}
			}
		}
		if (!candidates.isEmpty()) {
			pending = new PendingUse(player.getUUID(), hand, player.getItemInHand(hand).copy(), blockHit != null,
				candidates, player.position(), player.getYRot(), player.getXRot());
		}
	}

	/**
	 * Just after the right-click: the first candidate block that changed is what the click did
	 * (e.g. air → dirt when placing). If no block changed but the item did (filling a bottle at
	 * water), the clicked block is the condition. Clicks that changed nothing aren't recorded.
	 */
	public static void afterUse(ServerPlayer player) {
		PendingUse use = pending;
		pending = null;
		if (use == null || !use.player().equals(player.getUUID())) {
			return;
		}
		ServerLevel level = player.level();
		Session session = activeSession(player, level);
		if (session == null) {
			return;
		}
		Candidate changed = use.candidates().stream()
			.filter(c -> !level.getBlockState(c.pos()).equals(c.before()))
			.findFirst()
			.orElse(null);
		if (changed == null) {
			boolean itemChanged = !ItemStack.matches(use.itemBefore(), player.getItemInHand(use.hand()));
			if (!itemChanged) {
				return; // nothing happened (e.g. opening a chest): nothing to learn
			}
			changed = use.candidates().getFirst();
		}
		if (!checkInArea(player, session, changed.pos())) {
			return;
		}
		BlockHitResult hit = changed.hit();
		TaughtUse taught = new TaughtUse(changed.pos(), changed.before(), use.itemBefore(), use.onBlock(),
			hit.getBlockPos(), hit.getDirection(), hit.getLocation(), use.from(), use.yRot(), use.xRot());
		boolean replaces = session.uses.removeIf(taught::sameRule);
		if (!replaces && !checkRoom(player, session)) {
			return;
		}
		session.uses.add(taught);
		taught(player, session);
	}

	private static @Nullable Session activeSession(ServerPlayer player, ServerLevel level) {
		Session session = SESSIONS.get(player.getUUID());
		return session != null && level.dimension().equals(session.dimension) ? session : null;
	}

	private static boolean checkInArea(ServerPlayer player, Session session, BlockPos pos) {
		int radius = GolemTask.radiusFor(GolemTaskType.CUSTOM);
		if (Math.abs(pos.getX() - session.anchor.getX()) > radius || Math.abs(pos.getZ() - session.anchor.getZ()) > radius
			|| Math.abs(pos.getY() - session.anchor.getY()) > MAX_HEIGHT) {
			int size = radius * 2 + 1;
			player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_too_far", size, size));
			return false;
		}
		return true;
	}

	private static boolean checkRoom(ServerPlayer player, Session session) {
		if (session.steps() >= MAX_STEPS) {
			player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_full", MAX_STEPS));
			return false;
		}
		return true;
	}

	private static void taught(ServerPlayer player, Session session) {
		session.lastActivity = player.level().getGameTime();
		remind(player, session);
	}

	// ---- While teaching ----

	private static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Session> entry = it.next();
			Session session = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				it.remove(); // logged out
				continue;
			}
			long idle = player.level().getGameTime() - session.lastActivity;
			if (idle > TIMEOUT_TICKS || idle < 0) {
				it.remove();
				player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_timeout"));
				continue;
			}
			if (!player.level().dimension().equals(session.dimension)) {
				continue;
			}
			if (idle % MARKER_INTERVAL == 0) {
				showMarkers(player, Stream.concat(session.blocks.keySet().stream(), session.uses.stream().map(TaughtUse::pos)).distinct().toList());
			}
			if (idle % REMINDER_INTERVAL == 0) {
				remind(player, session); // the action bar fades, so keep it up
			}
		}
	}

	private static void remind(ServerPlayer player, Session session) {
		player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_progress", session.steps(), MAX_STEPS));
	}

	/** Particles on the corners of each taught spot, visible only to the teaching player. */
	private static void showMarkers(ServerPlayer player, List<BlockPos> positions) {
		ServerLevel level = player.level();
		for (BlockPos pos : positions) {
			for (int corner = 0; corner < 8; corner++) {
				double x = pos.getX() + ((corner & 1) == 0 ? -0.05 : 1.05);
				double y = pos.getY() + ((corner & 2) == 0 ? -0.05 : 1.05);
				double z = pos.getZ() + ((corner & 4) == 0 ? -0.05 : 1.05);
				level.sendParticles(player, MARKER, true, false, x, y, z, 1, 0, 0, 0, 0);
			}
		}
	}
}
