package com.smartergolems;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.golem.GolemStatus;
import com.smartergolems.golem.Teaching;
import com.smartergolems.network.ModNetworking;
import com.smartergolems.task.GolemTasks;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmarterGolems implements ModInitializer {
	public static final String MOD_ID = "smartergolems";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		SmarterGolemsConfig.load();
		GolemTasks.init();
		GolemInventory.init();
		GolemStatus.init();
		ModNetworking.init();
		Teaching.init();
		LOGGER.info("Smarter Golems loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
