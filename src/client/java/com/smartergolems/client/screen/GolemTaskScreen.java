package com.smartergolems.client.screen;

import com.smartergolems.golem.GolemInventory;
import com.smartergolems.network.ModNetworking;
import com.smartergolems.network.OpenTaskScreenPayload;
import com.smartergolems.network.SetTaskPayload;
import com.smartergolems.network.TakeItemsPayload;
import com.smartergolems.task.GolemTaskType;
import java.util.List;
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
import net.minecraft.world.item.ItemStack;

/**
 * Lets the player pick a task for a copper golem, see what it carries and take its items.
 * While open, particles outline the work area: copper-colored for the current area, pale for
 * a preview of where a new task would go. Players other than the task's owner see everything
 * but can't change it.
 */
public class GolemTaskScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_SPACING = 24;
	private static final int LINE = 12;
	private static final int SLOT = 18;
	private static final int PANEL_WIDTH = (GolemInventory.SIZE + 1) * SLOT + 24;
	private static final int PANEL_PADDING = 8;
	private static final int PANEL_COLOR = 0xA0000000;
	private static final int SLOT_COLOR = 0x80555555;
	private static final int TOOL_SLOT_COLOR = 0x80B87333;
	private static final int TEXT_GRAY = 0xFFA0A0A0;
	private static final int OUTLINE_INTERVAL_TICKS = 4;
	private static final DustParticleOptions CURRENT_AREA = new DustParticleOptions(0xE07B39, 1.0F);
	private static final DustParticleOptions PREVIEW_AREA = new DustParticleOptions(0xDDDDDD, 0.8F);

	private final OpenTaskScreenPayload data;
	private int ticks;

	public GolemTaskScreen(OpenTaskScreenPayload data) {
		super(Component.translatable("screen.smartergolems.title"));
		this.data = data;
	}

	// ---- Layout (top to bottom): title, current task, owner, task buttons, carried items, take button, area ----

	private int contentHeight() {
		int buttons = (GolemTaskType.values().length + 1) * BUTTON_SPACING;
		return LINE * 3 + 4 + buttons + LINE + SLOT + 6 + BUTTON_HEIGHT + 6 + LINE;
	}

	private int top() {
		return (this.height - contentHeight()) / 2;
	}

	private int buttonsTop() {
		return top() + LINE * 3 + 4;
	}

	private int itemsLabelTop() {
		return buttonsTop() + (GolemTaskType.values().length + 1) * BUTTON_SPACING;
	}

	private int slotsTop() {
		return itemsLabelTop() + LINE;
	}

	private int takeButtonTop() {
		return slotsTop() + SLOT + 6;
	}

	private int slotsLeft() {
		return (this.width - (GolemInventory.SIZE + 1) * SLOT - 6) / 2;
	}

	@Override
	protected void init() {
		int x = (this.width - BUTTON_WIDTH) / 2;
		int y = buttonsTop();
		for (GolemTaskType type : GolemTaskType.values()) {
			addTaskButton(type.displayName(), Optional.of(type), x, y);
			y += BUTTON_SPACING;
		}
		addTaskButton(Component.translatable("task.smartergolems.none"), Optional.empty(), x, y);

		Button take = Button.builder(Component.translatable("screen.smartergolems.take_items"), b ->
				ClientPlayNetworking.send(new TakeItemsPayload(data.golemId())))
			.bounds(x, takeButtonTop(), BUTTON_WIDTH, BUTTON_HEIGHT).build();
		take.active = data.canEdit() && data.bag().stream().anyMatch(stack -> !stack.isEmpty());
		this.addRenderableWidget(take);
	}

	private void addTaskButton(Component label, Optional<GolemTaskType> task, int x, int y) {
		Button button = Button.builder(label, b -> {
			ClientPlayNetworking.send(new SetTaskPayload(data.golemId(), task));
			this.onClose();
		}).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
		button.active = data.canEdit();
		this.addRenderableWidget(button);
	}

	@Override
	public void tick() {
		super.tick();
		// Close if the golem is gone or the player walked away.
		Entity entity = this.minecraft.level == null ? null : this.minecraft.level.getEntity(data.golemId());
		if (!(entity instanceof CopperGolem golem) || this.minecraft.player == null
			|| !this.minecraft.player.isWithinEntityInteractionRange(entity, ModNetworking.REACH_BUFFER)) {
			this.onClose();
			return;
		}
		if (ticks++ % OUTLINE_INTERVAL_TICKS == 0) {
			BlockPos center = data.anchor().orElse(golem.blockPosition());
			spawnOutline(center, data.anchor().isPresent() ? CURRENT_AREA : PREVIEW_AREA);
		}
	}

	/**
	 * Particles along the border of the square work area, at the level of the crops. A farm centered
	 * on a chest sits at ground level, so the outline goes one block up to stay visible.
	 */
	private void spawnOutline(BlockPos center, DustParticleOptions particle) {
		int radius = data.radius();
		double minX = center.getX() - radius;
		double minZ = center.getZ() - radius;
		double maxX = center.getX() + radius + 1;
		double maxZ = center.getZ() + radius + 1;
		boolean solidCenter = !this.minecraft.level.getBlockState(center).getCollisionShape(this.minecraft.level, center).isEmpty();
		double y = center.getY() + (solidCenter ? 1.2 : 0.2);
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
		int half = PANEL_WIDTH / 2;
		graphics.fill(this.width / 2 - half, top() - PANEL_PADDING, this.width / 2 + half, top() + contentHeight() + PANEL_PADDING, PANEL_COLOR);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int centerX = this.width / 2;
		int y = top();
		graphics.centeredText(this.font, this.title, centerX, y, -1);
		Component current = data.currentTask().map(GolemTaskType::displayName)
			.orElse(Component.translatable("task.smartergolems.none"));
		graphics.centeredText(this.font, Component.translatable("screen.smartergolems.current", current), centerX, y + LINE, TEXT_GRAY);
		if (!data.ownerName().isEmpty()) {
			Component owner = data.canEdit()
				? Component.translatable("screen.smartergolems.owner", data.ownerName())
				: Component.translatable("screen.smartergolems.owner_locked", data.ownerName());
			graphics.centeredText(this.font, owner, centerX, y + LINE * 2, data.canEdit() ? TEXT_GRAY : 0xFFFF8080);
		}

		graphics.centeredText(this.font, Component.translatable("screen.smartergolems.carrying"), centerX, itemsLabelTop() + 2, TEXT_GRAY);
		extractSlots(graphics, mouseX, mouseY);

		int size = data.radius() * 2 + 1;
		graphics.centeredText(this.font, Component.translatable("screen.smartergolems.area", size, size),
			centerX, takeButtonTop() + BUTTON_HEIGHT + 6, TEXT_GRAY);
	}

	/** The tool slot (copper-tinted) followed by the bag slots, with item tooltips on hover. */
	private void extractSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int x = slotsLeft();
		int y = slotsTop();
		drawSlot(graphics, data.tool(), x, y, TOOL_SLOT_COLOR, mouseX, mouseY, Component.translatable("screen.smartergolems.tool_slot"));
		x += SLOT + 6;
		List<ItemStack> bag = data.bag();
		for (int i = 0; i < GolemInventory.SIZE; i++) {
			drawSlot(graphics, i < bag.size() ? bag.get(i) : ItemStack.EMPTY, x + i * SLOT, y, SLOT_COLOR, mouseX, mouseY, null);
		}
	}

	private void drawSlot(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, int color, int mouseX, int mouseY,
	                      Component emptyTooltip) {
		graphics.fill(x, y, x + SLOT - 1, y + SLOT - 1, color);
		boolean hovered = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
		if (!stack.isEmpty()) {
			graphics.item(stack, x + 1, y + 1);
			graphics.itemDecorations(this.font, stack, x + 1, y + 1);
			if (hovered) {
				graphics.setTooltipForNextFrame(this.font, stack, mouseX, mouseY);
			}
		} else if (hovered && emptyTooltip != null) {
			graphics.setTooltipForNextFrame(this.font, emptyTooltip, mouseX, mouseY);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
