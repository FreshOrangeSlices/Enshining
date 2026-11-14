package com.freshorangeslices.enshining.client;

import java.util.List;

import javax.annotation.Nullable;

import org.joml.Quaternionf;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.core.CompanionEntity;
import com.freshorangeslices.enshining.core.CompanionMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.core.animation.RawAnimation;

/**
 * The companion menu: a big animated portrait of her in the middle (a client-only copy, so the real one keeps doing her
 * thing), armor slots down the left, the weapon slot on the right, quick toggles along the bottom, inventory below.
 * Hovering or changing the weapon slot zooms toward her hands and she holds the weapon up; hovering or changing an
 * armor slot zooms in on her upper body and she takes her "armor" pose. Drawn without texture files.
 */
public class CompanionScreen extends AbstractContainerScreen<CompanionMenu> {
	// Layout (relative to the menu's corner)
	private static final int PX0 = 48, PY0 = 8, PX1 = 208, PY1 = 152;          // portrait box
	private static final int BAR_Y = 160;                                        // the toggle bar
	private static final int ICON = 14;
	// Colors
	private static final int FRAME = 0xFF1E1B26, PANEL = 0xFF2A2633, TEXT = 0xFFEDE6D6, DIM = 0xFF9C94A8, GOLD = 0xFFD9B45A;

	// Menu poses: the character's own if her animation file has them, else her idle (so nothing ever T-poses).
	private static final String MENU_IDLE = "animation.companion.menu_idle";
	private static final String MENU_WEAPON = "animation.companion.menu_weapon";
	private static final String MENU_ARMOR = "animation.companion.menu_armor";
	private static final String IDLE = "animation.companion.idle";

	private enum Focus { FULL, WEAPON, ARMOR }

	/** Camera per focus: pixels per block, the body height (blocks) centred in the frame, and turn (degrees). */
	private static final float[] SCALE = {61.3F, 125.0F, 125.0F};
	private static final float[] AIM = {1.0F, 1.05F, 1.35F};
	private static final float[] TURN = {0.0F, 0.0F, 0.0F};

	// Tuning panel (beside the menu): lock a pose and set its camera by hand, to read off the numbers.
	@Nullable
	private Focus tuning;
	private float tuneScale, tuneAim, tuneTurn;

	@Nullable
	private CompanionEntity portrait;
	private Focus focus = Focus.FULL;
	private int focusHold;             // ticks a just-equipped focus stays even without hovering
	private float zoom, zoomO;         // 0 = full body, 1 = fully zoomed (eased)
	private float aimY, aimYO;         // the body height (blocks) the camera centres on
	private final ItemStack[] lastGear = new ItemStack[5];

	public CompanionScreen(CompanionMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		imageWidth = 256;
		imageHeight = 268;
		inventoryLabelX = CompanionMenu.INV_X;
		inventoryLabelY = CompanionMenu.INV_Y - 11;
		titleLabelX = PX0 + 6;
		titleLabelY = PY0 + 5;
	}

	@Override
	protected void init() {
		super.init();
		CompanionEntity real = menu.companion();
		if (real != null && minecraft != null && minecraft.level != null && portrait == null
				&& real.getType().create(minecraft.level) instanceof CompanionEntity copy) {
			portrait = copy;
			portrait.copyLookFrom(real);
			for (int i = 0; i < 5; i++) {
				lastGear[i] = real.getItemBySlot(CompanionMenu.SLOTS[i]).copy();
			}
		}
		aimY = aimYO = 1.0F;
		addTuningPanel();
	}

	private void addTuningPanel() {
		int w = 112;
		int tx = leftPos + imageWidth + 6 + w <= width ? leftPos + imageWidth + 6 : Math.max(2, leftPos - w - 6);
		int ty = topPos + 8;
		addRenderableWidget(net.minecraft.client.gui.components.Button.builder(tuningLabel(), b -> {
			tuning = tuning == null ? Focus.FULL : tuning == Focus.ARMOR ? null : Focus.values()[tuning.ordinal() + 1];
			if (tuning != null) {
				tuneScale = SCALE[tuning.ordinal()];
				tuneAim = AIM[tuning.ordinal()];
				tuneTurn = TURN[tuning.ordinal()];
			}
			rebuildWidgets();
		}).bounds(tx, ty, w, 18).build());
		if (tuning == null) {
			return;
		}
		addRenderableWidget(new Tune(tx, ty + 22, w, 20, 20, 260, () -> tuneScale, v -> tuneScale = v, "enshining.menu.tune.zoom", "%.0f"));
		addRenderableWidget(new Tune(tx, ty + 44, w, 20, -0.5F, 2.5F, () -> tuneAim, v -> tuneAim = v, "enshining.menu.tune.height", "%.2f"));
		addRenderableWidget(new Tune(tx, ty + 66, w, 20, -180, 180, () -> tuneTurn, v -> tuneTurn = v, "enshining.menu.tune.turn", "%.0f"));
	}

	private Component tuningLabel() {
		return Component.translatable("enshining.menu.tune", Component.translatable(tuning == null ? "enshining.menu.off"
				: "enshining.menu.tune." + tuning.name().toLowerCase()));
	}

	/** A slider over [min, max] that shows its value. */
	private static class Tune extends net.minecraft.client.gui.components.AbstractSliderButton {
		private final float min, max;
		private final java.util.function.Consumer<Float> set;
		private final String key, format;

		Tune(int x, int y, int w, int h, float min, float max, java.util.function.Supplier<Float> get,
				java.util.function.Consumer<Float> set, String key, String format) {
			super(x, y, w, h, Component.empty(), (get.get() - min) / (max - min));
			this.min = min;
			this.max = max;
			this.set = set;
			this.key = key;
			this.format = format;
			updateMessage();
		}

		private float current() {
			return (float) (min + value * (max - min));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable(key, String.format(java.util.Locale.ROOT, format, current())));
		}

		@Override
		protected void applyValue() {
			set.accept(current());
		}
	}

	// ---- Poses and camera -------------------------------------------------------------------------------------

	private RawAnimation pose(String wanted) {
		CompanionEntity real = menu.companion();
		String name = wanted;
		if (real != null) {
			ResourceLocation file = new ResourceLocation(Enshining.MODID, "animations/entity/"
					+ net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(real.getType()).getPath() + ".animation.json");
			var baked = GeckoLibCache.getBakedAnimations().get(file);
			if (baked == null || baked.getAnimation(wanted) == null) {
				name = baked != null && baked.getAnimation(MENU_IDLE) != null ? MENU_IDLE : IDLE;
			}
		}
		return RawAnimation.begin().thenLoop(name);
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		CompanionEntity real = menu.companion();
		if (portrait == null || real == null) {
			return;
		}
		portrait.tickCount++; // not in the world, so its animations need their clock moved by hand
		portrait.copyLookFrom(real);

		// Something just went on (or came off): turn to it for a moment.
		for (int i = 0; i < 5; i++) {
			ItemStack now = real.getItemBySlot(CompanionMenu.SLOTS[i]);
			if (!ItemStack.matches(now, lastGear[i])) {
				lastGear[i] = now.copy();
				focus = i == 4 ? Focus.WEAPON : Focus.ARMOR;
				focusHold = 50;
			}
		}
		int hoveredIndex = hoveredSlot == null ? -1 : menu.slots.indexOf(hoveredSlot);
		Focus hovered = hoveredIndex < 0 || hoveredIndex > 4 ? null : hoveredIndex == 4 ? Focus.WEAPON : Focus.ARMOR;
		if (tuning != null) {
			focus = tuning; // locked while tuning
		} else if (hovered != null) {
			focus = hovered;
			focusHold = Math.max(focusHold, 10);
		} else if (focusHold > 0 && --focusHold == 0) {
			focus = Focus.FULL;
		}

		switch (focus) {
			// Her own weapon (or a swapped one) comes out; it's always there, so only the visibility toggle hides it.
			case WEAPON -> portrait.setMenuPose(pose(MENU_WEAPON), real.setting(CompanionEntity.SHOW_WEAPON));
			case ARMOR -> portrait.setMenuPose(pose(MENU_ARMOR), false);
			default -> portrait.setMenuPose(pose(MENU_IDLE), false);
		}

		zoomO = zoom;
		aimYO = aimY;
		float targetZoom = focus == Focus.FULL ? 0.0F : 1.0F;
		float targetAim = AIM[focus.ordinal()]; // full: middle of her body; weapon: hands and blade; armor: chest
		zoom += (targetZoom - zoom) * 0.25F;
		aimY += (targetAim - aimY) * 0.25F;
	}

	// ---- Drawing ----------------------------------------------------------------------------------------------

	@Override
	protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
		int x = leftPos, y = topPos;
		CompanionEntity real = menu.companion();
		int element = real == null ? GOLD : 0xFF000000 | real.element().color();

		g.fill(x, y, x + imageWidth, y + imageHeight, FRAME);
		g.fill(x + 2, y + 2, x + imageWidth - 2, y + imageHeight - 2, PANEL);
		// Portrait backdrop: her element's color fading into the dark.
		g.fillGradient(x + PX0, y + PY0, x + PX1, y + PY1, withAlpha(element, 0x90), 0xFF15131B);
		outline(g, x + PX0 - 1, y + PY0 - 1, x + PX1 + 1, y + PY1 + 1, withAlpha(element, 0xFF));

		// Equipment: round frames (armor left, weapon right), lit when filled.
		for (int i = 0; i < 5; i++) {
			var slot = menu.slots.get(i);
			boolean filled = slot.hasItem();
			ring(g, x + slot.x + 8, y + slot.y + 8, 13, filled ? withAlpha(element, 0xFF) : 0xFF5A5266, filled ? withAlpha(element, 0x55) : 0x40000000);
		}
		// Player inventory slots.
		for (int i = 5; i < menu.slots.size(); i++) {
			var slot = menu.slots.get(i);
			g.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, 0xFF15131B);
			g.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, 0xFF3A3445);
		}

		drawPortrait(g, x, y, partialTick, mouseX, mouseY);
		drawBar(g, x, y, real, mouseX, mouseY);
	}

	private void drawPortrait(GuiGraphics g, int x, int y, float partialTick, int mouseX, int mouseY) {
		if (portrait == null) {
			return;
		}
		float z = Mth.lerp(partialTick, zoomO, zoom);
		float aim = Mth.lerp(partialTick, aimYO, aimY);
		float eased = z * z * (3 - 2 * z);
		int boxH = PY1 - PY0;
		int to = focus.ordinal();
		float scale = Mth.lerp(eased, SCALE[0], SCALE[to == 0 ? 1 : to]);   // pixels per block: full body -> close-up
		float turn = Mth.lerp(eased, TURN[0], TURN[to]);
		if (tuning != null) {
			scale = tuneScale;
			aim = tuneAim;
			turn = tuneTurn;
		}
		int cx = x + (PX0 + PX1) / 2;
		int feetY = (int) (y + PY0 + boxH / 2.0F + aim * scale);         // so the aimed height sits mid-box
		float lookX = (cx - mouseX) / 40.0F, lookY = (y + PY0 + boxH / 3.0F - mouseY) / 40.0F;

		Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
		Quaternionf tilt = new Quaternionf().rotateX(Mth.clamp(lookY, -1, 1) * 8.0F * Mth.DEG_TO_RAD);
		pose.mul(tilt);
		float yaw = 180.0F + turn; // 0 = facing straight out of the screen
		portrait.yBodyRot = yaw;
		portrait.yBodyRotO = yaw;
		portrait.setYRot(yaw + Mth.clamp(lookX, -1, 1) * 25.0F);
		portrait.setXRot(-Mth.clamp(lookY, -1, 1) * 15.0F);
		portrait.yHeadRot = portrait.getYRot();
		portrait.yHeadRotO = portrait.getYRot();

		g.enableScissor(x + PX0, y + PY0, x + PX1, y + PY1);
		InventoryScreen.renderEntityInInventory(g, cx, feetY, (int) scale, pose, tilt, portrait);
		g.disableScissor();
	}

	/** The bottom bar: armor (eye), weapon (sword), behavior (colored dot) on the left; skill and burst on the right. */
	private void drawBar(GuiGraphics g, int x, int y, @Nullable CompanionEntity c, int mouseX, int mouseY) {
		if (c == null) {
			return;
		}
		int by = y + BAR_Y;
		Icon.EYE.draw(g, x + PX0, by, c.setting(CompanionEntity.SHOW_ARMOR));
		Icon.SWORD.draw(g, x + PX0 + 20, by, c.setting(CompanionEntity.SHOW_WEAPON));
		int dot = switch (c.behavior()) {
			case PASSIVE -> 0xFF4FC86A;
			case WAIT_FOR_LEADER -> 0xFFE8C84A;
			default -> 0xFFE0504A;
		};
		disc(g, x + PX0 + 40 + 7, by + 7, 5, dot);
		Icon.MODE.draw(g, x + PX0 + 60, by, true);
		Icon.SKILL.draw(g, x + PX1 - 34, by, c.setting(CompanionEntity.SKILL_ON));
		Icon.BURST.draw(g, x + PX1 - 14, by, c.setting(CompanionEntity.BURST_ON));
	}

	@Override
	protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
		g.drawString(font, title, titleLabelX, titleLabelY, TEXT, true);
		g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, DIM, false);
		CompanionEntity c = menu.companion();
		if (c == null) {
			return;
		}
		// Stats, top-right of the portrait.
		int sx = PX1 - 4, sy = PY0 + 5;
		rightText(g, Component.translatable("enshining.menu.level", menu.level()), sx, sy, GOLD);
		rightText(g, Component.translatable("enshining.menu.hp", (int) Math.ceil(c.getHealth()), (int) c.getMaxHealth()), sx, sy + 10, TEXT);
		rightText(g, Component.translatable("enshining.menu.atk", fmt(c.getAttributeValue(Attributes.ATTACK_DAMAGE))), sx, sy + 20, TEXT);
		rightText(g, Component.translatable("enshining.menu.def", fmt(c.getAttributeValue(Attributes.ARMOR))), sx, sy + 30, TEXT);
		// XP and energy bars along the bottom of the portrait.
		bar(g, PX0 + 6, PY1 - 14, PX1 - PX0 - 12, menu.xpToNext() == 0 ? 0 : menu.xp() / (float) menu.xpToNext(), 0xFF8FB4FF);
		bar(g, PX0 + 6, PY1 - 8, PX1 - PX0 - 12, menu.burstCost() == 0 ? 0 : Math.min(1, menu.energy() / (float) menu.burstCost()),
				0xFF000000 | c.element().color());
	}

	private void rightText(GuiGraphics g, Component text, int rightX, int y, int color) {
		g.drawString(font, text, rightX - font.width(text), y, color, true);
	}

	private static String fmt(double v) {
		return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		renderBackground(g);
		super.render(g, mouseX, mouseY, partialTick);
		renderTooltip(g, mouseX, mouseY);
		Component tip = barTooltip(mouseX - leftPos, mouseY - topPos);
		if (tip != null) {
			g.renderTooltip(font, List.of(tip.getVisualOrderText()), mouseX, mouseY);
		}
	}

	// ---- Bar clicks and tooltips ------------------------------------------------------------------------------

	private int barButtonAt(int mx, int my) {
		if (my < BAR_Y || my >= BAR_Y + ICON) {
			return -1;
		}
		int[][] spots = {{PX0, CompanionMenu.BUTTON_ARMOR}, {PX0 + 20, CompanionMenu.BUTTON_WEAPON}, {PX0 + 40, CompanionMenu.BUTTON_BEHAVIOR},
				{PX0 + 60, CompanionMenu.BUTTON_MODE}, {PX1 - 34, CompanionMenu.BUTTON_SKILL}, {PX1 - 14, CompanionMenu.BUTTON_BURST}};
		for (int[] s : spots) {
			if (mx >= s[0] && mx < s[0] + ICON) {
				return s[1];
			}
		}
		return -1;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		int id = barButtonAt((int) mouseX - leftPos, (int) mouseY - topPos);
		if (id >= 0 && minecraft != null && minecraft.gameMode != null) {
			minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
			minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
					net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Nullable
	private Component barTooltip(int mx, int my) {
		CompanionEntity c = menu.companion();
		int id = barButtonAt(mx, my);
		if (c == null || id < 0) {
			return null;
		}
		return switch (id) {
			case CompanionMenu.BUTTON_ARMOR -> onOff("enshining.menu.armor", c.setting(CompanionEntity.SHOW_ARMOR));
			case CompanionMenu.BUTTON_WEAPON -> onOff("enshining.menu.weapon", c.setting(CompanionEntity.SHOW_WEAPON));
			case CompanionMenu.BUTTON_BEHAVIOR -> Component.translatable("enshining.behavior." + c.behavior().name().toLowerCase());
			case CompanionMenu.BUTTON_MODE -> Component.translatable("enshining.menu.mode",
					Component.translatable("enshining.mode." + c.mode().name().toLowerCase()));
			case CompanionMenu.BUTTON_SKILL -> onOff("enshining.menu.skill", c.setting(CompanionEntity.SKILL_ON));
			default -> onOff("enshining.menu.burst", c.setting(CompanionEntity.BURST_ON));
		};
	}

	private static Component onOff(String key, boolean on) {
		return Component.translatable(key, Component.translatable(on ? "enshining.menu.on" : "enshining.menu.off"));
	}

	// ---- Small drawing helpers (no texture files) -------------------------------------------------------------

	private static int withAlpha(int argb, int alpha) {
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	private static void outline(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0, y0, x1, y0 + 1, color);
		g.fill(x0, y1 - 1, x1, y1, color);
		g.fill(x0, y0, x0 + 1, y1, color);
		g.fill(x1 - 1, y0, x1, y1, color);
	}

	private static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.round(Math.sqrt(r * r - dy * dy));
			g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/** A round slot frame: a filled disc with a ring around it. */
	private static void ring(GuiGraphics g, int cx, int cy, int r, int ringColor, int fillColor) {
		disc(g, cx, cy, r, ringColor);
		disc(g, cx, cy, r - 1, 0xFF15131B);
		disc(g, cx, cy, r - 1, fillColor);
	}

	private static void bar(GuiGraphics g, int x, int y, int w, float fraction, int color) {
		g.fill(x, y, x + w, y + 3, 0x80000000);
		g.fill(x, y, x + Math.round(w * Mth.clamp(fraction, 0, 1)), y + 3, color);
	}

	/** Tiny pixel icons, drawn from 7x7 masks at 2x; dimmed when off. */
	private enum Icon {
		EYE(".......", "..###..", ".#...#.", "#..#..#", ".#...#.", "..###..", "......."),
		SWORD("......#", ".....#.", "....#..", "#..#...", ".##....", ".##....", "#..#..."),
		MODE("...#...", "..###..", ".#####.", "...#...", "...#...", "...#...", "..###.."),
		SKILL("...#...", "..###..", ".#####.", "#######", ".#####.", "..###..", "...#..."),
		BURST("#..#..#", ".#.#.#.", "..###..", "#######", "..###..", ".#.#.#.", "#..#..#");

		private final String[] rows;

		Icon(String... rows) {
			this.rows = rows;
		}

		void draw(GuiGraphics g, int x, int y, boolean on) {
			int color = on ? GOLD : 0xFF5A5266;
			for (int r = 0; r < rows.length; r++) {
				for (int c = 0; c < rows[r].length(); c++) {
					if (rows[r].charAt(c) == '#') {
						g.fill(x + c * 2, y + r * 2, x + c * 2 + 2, y + r * 2 + 2, color);
					}
				}
			}
			if (!on) { // a slash through it when off
				for (int i = 0; i < 7; i++) {
					g.fill(x + i * 2, y + i * 2, x + i * 2 + 2, y + i * 2 + 2, 0xFFB04A4A);
				}
			}
		}
	}
}
