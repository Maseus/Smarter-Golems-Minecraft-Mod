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
	private static final int MIN_FARM_RADIUS = 1;
	private static final int MAX_FARM_RADIUS = 16;
	private static SmarterGolemsConfig instance = new SmarterGolemsConfig();

	/** Blocks from the center to the edge of a farm. 7 gives a 15x15 area (2 * 7 + 1). */
	public int farmRadius = 7;

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
				SmarterGolems.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		instance = loaded != null ? loaded : new SmarterGolemsConfig();
		instance.farmRadius = Math.clamp(instance.farmRadius, MIN_FARM_RADIUS, MAX_FARM_RADIUS);

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
