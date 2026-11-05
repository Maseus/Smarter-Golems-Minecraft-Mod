package com.smartergolems.client.screen;

import com.smartergolems.network.OpenTaskScreenPayload;
import com.smartergolems.network.SetTaskPayload;
import com.smartergolems.task.GolemTaskType;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;

/**
 * Lets the player pick a task for a copper golem. While open, particles outline the work area:
 * copper-colored for the current area, pale for a preview of where a new task would go.
 */
public class GolemTaskScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_SPACING = 24;
	private static final int PANEL_PADDING = 10;
	private static final int PANEL_COLOR = 0xA0000000;
	/** Same reach buffer the server allows, so the screen closes before the server would reject a choice. */
	private static final double REACH_BUFFER = 4.0;
	private static final int OUTLINE_INTERVAL_TICKS = 4;
	private static final DustParticleOptions CURRENT_AREA = new DustParticleOptions(0xE07B39, 1.0F);
	private static final DustParticleOptions PREVIEW_AREA = new DustParticleOptions(0xDDDDDD, 0.8F);

	private final int golemId;
	private final Optional<GolemTaskType> currentTask;
	private final Optional<BlockPos> anchor;
	private final int radius;
	private int ticks;

	public GolemTaskScreen(OpenTaskScreenPayload payload) {
		super(Component.translatable("screen.smartergolems.title"));
		this.golemId = payload.golemId();
		this.currentTask = payload.currentTask();
		this.anchor = payload.anchor();
		this.radius = payload.radius();
	}

	private int buttonCount() {
		return GolemTaskType.values().length + 1;
	}

	private int buttonsTop() {
		return this.height / 2 - BUTTON_SPACING;
	}

	private int buttonsBottom() {
		return buttonsTop() + (buttonCount() - 1) * BUTTON_SPACING + BUTTON_HEIGHT;
	}

	@Override
	protected void init() {
		int x = (this.width - BUTTON_WIDTH) / 2;
		int y = buttonsTop();

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
		if (!(entity instanceof CopperGolem golem) || this.minecraft.player == null
			|| !this.minecraft.player.isWithinEntityInteractionRange(entity, REACH_BUFFER)) {
			this.onClose();
			return;
		}
		if (ticks++ % OUTLINE_INTERVAL_TICKS == 0) {
			BlockPos center = anchor.orElse(golem.blockPosition());
			spawnOutline(center, anchor.isPresent() ? CURRENT_AREA : PREVIEW_AREA);
		}
	}

	/** Particles along the border of the square work area, at the level of the crops. */
	private void spawnOutline(BlockPos center, DustParticleOptions particle) {
		double minX = center.getX() - radius;
		double minZ = center.getZ() - radius;
		double maxX = center.getX() + radius + 1;
		double maxZ = center.getZ() + radius + 1;
		double y = center.getY() + 0.2;
		for (double d = 0; d <= maxX - minX; d += 0.5) {
			this.minecraft.level.addParticle(particle, minX + d, y, minZ, 0, 0, 0);
			this.minecraft.level.addParticle(particle, minX + d, y, maxZ, 0, 0, 0);
			this.minecraft.level.addParticle(particle, minX, y, minZ + d, 0, 0, 0);
			this.minecraft.level.addParticle(particle, maxX, y, minZ + d, 0, 0, 0);
		}
	}

	/** A small translucent panel instead of the usual blur, so the area outline in the world stays visible. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int halfWidth = BUTTON_WIDTH / 2 + PANEL_PADDING;
		graphics.fill(this.width / 2 - halfWidth, buttonsTop() - 36 - PANEL_PADDING,
			this.width / 2 + halfWidth, buttonsBottom() + 12 + PANEL_PADDING, PANEL_COLOR);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int centerX = this.width / 2;
		int top = buttonsTop();
		graphics.centeredText(this.font, this.title, centerX, top - 36, -1);
		Component current = currentTask.map(GolemTaskType::displayName)
			.orElse(Component.translatable("task.smartergolems.none"));
		graphics.centeredText(this.font, Component.translatable("screen.smartergolems.current", current), centerX, top - 20, 0xFFA0A0A0);
		int size = radius * 2 + 1;
		graphics.centeredText(this.font, Component.translatable("screen.smartergolems.area", size, size), centerX, buttonsBottom() + 6, 0xFFA0A0A0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
