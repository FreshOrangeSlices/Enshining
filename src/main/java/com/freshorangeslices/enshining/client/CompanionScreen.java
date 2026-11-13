package com.freshorangeslices.enshining.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.freshorangeslices.enshining.core.CompanionEntity;
import com.freshorangeslices.enshining.core.CompanionMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** The companion menu: her equipment, a live preview of her, setting buttons and her status. Drawn without textures. */
public class CompanionScreen extends AbstractContainerScreen<CompanionMenu> {
	private static final int PANEL = 0xFFC6C6C6, LIGHT = 0xFFFFFFFF, DARK = 0xFF555555, SLOT = 0xFF8B8B8B, PREVIEW = 0xFF2B2B2B;
	private final List<Runnable> relabel = new ArrayList<>();

	public CompanionScreen(CompanionMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		imageWidth = 176;
		imageHeight = 230;
		inventoryLabelY = 137;
	}

	@Override
	protected void init() {
		super.init();
		relabel.clear();
		CompanionEntity c = menu.companion();
		if (c == null) {
			return;
		}
		button(0, CompanionMenu.BUTTON_MODE, () -> Component.translatable("enshining.menu.mode",
				Component.translatable("enshining.mode." + c.mode().name().toLowerCase())));
		button(1, CompanionMenu.BUTTON_BEHAVIOR, () -> Component.translatable("enshining.behavior." + c.behavior().name().toLowerCase()));
		button(2, CompanionMenu.BUTTON_ARMOR, () -> onOff("enshining.menu.armor", c.setting(CompanionEntity.SHOW_ARMOR)));
		button(3, CompanionMenu.BUTTON_WEAPON, () -> onOff("enshining.menu.weapon", c.setting(CompanionEntity.SHOW_WEAPON)));
		button(4, CompanionMenu.BUTTON_SKILL, () -> onOff("enshining.menu.skill", c.setting(CompanionEntity.SKILL_ON)));
		button(5, CompanionMenu.BUTTON_BURST, () -> onOff("enshining.menu.burst", c.setting(CompanionEntity.BURST_ON)));
	}

	private static Component onOff(String key, boolean on) {
		return Component.translatable(key, Component.translatable(on ? "enshining.menu.on" : "enshining.menu.off"));
	}

	private void button(int row, int id, Supplier<Component> label) {
		Button button = Button.builder(label.get(), b -> {
			if (minecraft != null && minecraft.gameMode != null) {
				minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
			}
		}).bounds(leftPos + 84, topPos + 16 + row * 17, 86, 15).build();
		addRenderableWidget(button);
		relabel.add(() -> button.setMessage(label.get()));
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		relabel.forEach(Runnable::run); // settings are synced on the entity; show their current state
	}

	@Override
	protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
		int x = leftPos, y = topPos;
		g.fill(x, y, x + imageWidth, y + imageHeight, DARK);
		g.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, LIGHT);
		g.fill(x + 2, y + 2, x + imageWidth - 2, y + imageHeight - 2, PANEL);
		for (Slot s : slotsOf()) {
			g.fill(x + s.x - 1, y + s.y - 1, x + s.x + 17, y + s.y + 17, DARK);
			g.fill(x + s.x, y + s.y, x + s.x + 17, y + s.y + 17, LIGHT);
			g.fill(x + s.x, y + s.y, x + s.x + 16, y + s.y + 16, SLOT);
		}
		g.fill(x + 28, y + 16, x + 80, y + 114, PREVIEW);
		CompanionEntity c = menu.companion();
		if (c != null) {
			InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + 54, y + 106, 30, x + 54 - mouseX, y + 50 - mouseY, c);
		}
	}

	private List<Slot> slotsOf() {
		List<Slot> out = new ArrayList<>();
		menu.slots.forEach(s -> out.add(new Slot(s.x, s.y)));
		return out;
	}

	private record Slot(int x, int y) {
	}

	@Override
	protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
		super.renderLabels(g, mouseX, mouseY);
		CompanionEntity c = menu.companion();
		int hp = c == null ? 0 : (int) Math.ceil(c.getHealth()), maxHp = c == null ? 0 : (int) c.getMaxHealth();
		g.drawString(font, Component.translatable("enshining.menu.status", menu.level(), menu.xp(), menu.xpToNext()), 8, 118, 0x404040, false);
		g.drawString(font, Component.translatable("enshining.menu.status2", hp, maxHp, menu.energy(), menu.burstCost()), 8, 127, 0x404040, false);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		renderBackground(g);
		super.render(g, mouseX, mouseY, partialTick);
		renderTooltip(g, mouseX, mouseY);
	}
}
