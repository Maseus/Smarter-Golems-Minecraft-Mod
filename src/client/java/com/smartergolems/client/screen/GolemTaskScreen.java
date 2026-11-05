package com.smartergolems.client.screen;

import com.smartergolems.network.SetTaskPayload;
import com.smartergolems.task.GolemTaskType;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;

/** Lets the player pick a task for a copper golem. */
public class GolemTaskScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_SPACING = 24;
	/** Same reach buffer the server allows, so the screen closes before the server would reject a choice. */
	private static final double REACH_BUFFER = 4.0;

	private final int golemId;
	private final Optional<GolemTaskType> currentTask;

	public GolemTaskScreen(int golemId, Optional<GolemTaskType> currentTask) {
		super(Component.translatable("screen.smartergolems.title"));
		this.golemId = golemId;
		this.currentTask = currentTask;
	}

	@Override
	protected void init() {
		int x = (this.width - BUTTON_WIDTH) / 2;
		int y = this.height / 2 - BUTTON_SPACING;

		for (GolemTaskType type : GolemTaskType.values()) {
			this.addRenderableWidget(Button.builder(type.displayName(), b -> choose(Optional.of(type)))
				.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
			y += BUTTON_SPACING;
		}
		this.addRenderableWidget(Button.builder(Component.translatable("task.smartergolems.none"), b -> choose(Optional.empty()))
			.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	private void choose(Optional<GolemTaskType> task) {
		ClientPlayNetworking.send(new SetTaskPayload(golemId, task));
		this.onClose();
	}

	@Override
	public void tick() {
		super.tick();
		// Close if the golem is gone or the player walked away.
		Entity entity = this.minecraft.level == null ? null : this.minecraft.level.getEntity(golemId);
		if (!(entity instanceof CopperGolem) || this.minecraft.player == null
			|| !this.minecraft.player.isWithinEntityInteractionRange(entity, REACH_BUFFER)) {
			this.onClose();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int centerX = this.width / 2;
		int top = this.height / 2 - BUTTON_SPACING;
		graphics.centeredText(this.font, this.title, centerX, top - 36, -1);
		Component current = currentTask.map(GolemTaskType::displayName)
			.orElse(Component.translatable("task.smartergolems.none"));
		graphics.centeredText(this.font, Component.translatable("screen.smartergolems.current", current), centerX, top - 20, 0xFFA0A0A0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
