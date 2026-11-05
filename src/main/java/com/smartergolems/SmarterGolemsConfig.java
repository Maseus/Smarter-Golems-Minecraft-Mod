package com.smartergolems;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings loaded from {@code config/smartergolems.json}. The file is created with
 * defaults on first launch; restart the game (or server) after editing it.
 */
public final class SmarterGolemsConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MIN_RADIUS = 1;
	private static final int MAX_RADIUS = 16;
	private static SmarterGolemsConfig instance = new SmarterGolemsConfig();

	/**
	 * Blocks from the center to the edge of a farm. 4 gives a 9x9 area (2 * 4 + 1): exactly the
	 * farmland one water source keeps wet, e.g. around a waterlogged chest in the middle.
	 */
	public int farmingRadius = 4;

	/** Blocks from the center to the edge of the area for other tasks. 7 gives 15x15. */
	public int areaRadius = 7;

	/** Before 1.1.0 one radius ({@code farmRadius}) applied to all tasks; read it once as {@link #areaRadius}. */
	private Integer farmRadius;

	public static SmarterGolemsConfig get() {
		return instance;
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(SmarterGolems.MOD_ID + ".json");
		SmarterGolemsConfig loaded = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				loaded = GSON.fromJson(reader, SmarterGolemsConfig.class);
			} catch (IOException | RuntimeException e) {
				// Keep the player's file so they can fix the mistake; just run on defaults for now.
				SmarterGolems.LOGGER.warn("Could not read {} (using defaults until it's fixed; the file was left unchanged)", file, e);
				instance = new SmarterGolemsConfig();
				return;
			}
		}
		instance = loaded != null ? loaded : new SmarterGolemsConfig();
		if (instance.farmRadius != null) {
			instance.areaRadius = instance.farmRadius;
			instance.farmRadius = null; // null fields aren't written back, so the old key goes away
		}
		instance.farmingRadius = Math.clamp(instance.farmingRadius, MIN_RADIUS, MAX_RADIUS);
		instance.areaRadius = Math.clamp(instance.areaRadius, MIN_RADIUS, MAX_RADIUS);

		// Write back so new options appear in the file and out-of-range values are corrected.
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			SmarterGolems.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
