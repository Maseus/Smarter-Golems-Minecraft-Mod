package com.smartergolems.golem;

import com.smartergolems.task.GolemTask;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.task.TaughtBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Teaching a golem its custom task by example. After the player picks "Custom" in the task
 * screen, every block they break near the golem is recorded; sneak + right-clicking the golem
 * finishes and gives it the task. Sessions live only in memory and time out.
 */
public final class Teaching {
	public static final int MAX_BLOCKS = 16;
	/** Taught blocks may be this many blocks above or below where the golem stood. */
	public static final int MAX_HEIGHT = 4;
	private static final int TIMEOUT_TICKS = 3 * 60 * 20;
	private static final int MARKER_INTERVAL = 10;
	private static final int REMINDER_INTERVAL = 60;
	private static final DustParticleOptions MARKER = new DustParticleOptions(0xE07B39, 1.0F);

	/** A player teaching one golem: where its area is centered and what they've broken so far. */
	private static final class Session {
		final UUID golem;
		final ResourceKey<Level> dimension;
		final BlockPos anchor;
		final long startedAt;
		final Map<BlockPos, TaughtBlock> blocks = new LinkedHashMap<>();

		Session(UUID golem, ResourceKey<Level> dimension, BlockPos anchor, long startedAt) {
			this.golem = golem;
			this.dimension = dimension;
			this.anchor = anchor;
			this.startedAt = startedAt;
		}
	}

	/** Teaching sessions by player. */
	private static final Map<UUID, Session> SESSIONS = new HashMap<>();

	private Teaching() {}

	public static void init() {
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
				onBlockBroken(serverPlayer, serverLevel, pos, state, blockEntity != null);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(Teaching::tick);
		// Sessions are kept in a static map: don't carry them into the next world in singleplayer.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.clear());
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
		if (session.blocks.isEmpty()) {
			player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_nothing"));
			return;
		}
		GolemInteraction.applyCustomTask(golem, player, session.anchor, new ArrayList<>(session.blocks.values()));
	}

	private static void onBlockBroken(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, boolean hadBlockEntity) {
		Session session = SESSIONS.get(player.getUUID());
		if (session == null || !level.dimension().equals(session.dimension)) {
			return;
		}
		int radius = GolemTask.radiusFor(GolemTaskType.CUSTOM);
		if (Math.abs(pos.getX() - session.anchor.getX()) > radius || Math.abs(pos.getZ() - session.anchor.getZ()) > radius
			|| Math.abs(pos.getY() - session.anchor.getY()) > MAX_HEIGHT) {
			int size = radius * 2 + 1;
			player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_too_far", size, size));
			return;
		}
		// The block is gone by now, so check what it was (the state from before it broke).
		if (hadBlockEntity || state.isAir() || !state.getFluidState().isEmpty() || state.getBlock().defaultDestroyTime() < 0) {
			player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_not_allowed"));
			return;
		}
		if (!session.blocks.containsKey(pos) && session.blocks.size() >= MAX_BLOCKS) {
			player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_full", MAX_BLOCKS));
			return;
		}
		session.blocks.put(pos.immutable(), new TaughtBlock(pos.immutable(), state.getBlock()));
		remind(player, session);
	}

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
			long elapsed = player.level().getGameTime() - session.startedAt;
			if (elapsed > TIMEOUT_TICKS || elapsed < 0) {
				it.remove();
				player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_timeout"));
				continue;
			}
			if (!player.level().dimension().equals(session.dimension)) {
				continue;
			}
			if (elapsed % MARKER_INTERVAL == 0) {
				showMarkers(player, session.blocks.keySet());
			}
			if (elapsed % REMINDER_INTERVAL == 0) {
				remind(player, session); // the action bar fades, so keep it up
			}
		}
	}

	private static void remind(ServerPlayer player, Session session) {
		player.sendOverlayMessage(Component.translatable("message.smartergolems.teach_progress", session.blocks.size(), MAX_BLOCKS));
	}

	/** Particles on the corners of each taught spot, visible only to the teaching player. */
	private static void showMarkers(ServerPlayer player, Iterable<BlockPos> positions) {
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
