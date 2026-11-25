package com.freshorangeslices.enshining.core;

import javax.annotation.Nullable;

import com.freshorangeslices.enshining.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The companion menu: her 4 armor slots and weapon slot (no other storage), plus buttons for her mode, behavior,
 * visual toggles and skill/burst switches. The buttons go through vanilla's menu-button packet, so no custom networking.
 */
public class CompanionMenu extends AbstractContainerMenu {
	public static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
			EquipmentSlot.MAINHAND};
	/** Layout (shared with the screen): armor down the left of her portrait, the weapon on its right, inventory below. */
	public static final int ARMOR_X = 22, ARMOR_Y = 26, ARMOR_STEP = 30, WEAPON_X = 218, WEAPON_Y = 70, INV_X = 48, INV_Y = 186;
	public static final int BUTTON_MODE = 0, BUTTON_BEHAVIOR = 1, BUTTON_ARMOR = 2, BUTTON_WEAPON = 3, BUTTON_SKILL = 4,
			BUTTON_BURST = 5, BUTTON_PLAY = 6;

	@Nullable
	private final CompanionEntity companion;
	private final ContainerData data;

	/** Server side. */
	public CompanionMenu(int id, Inventory inventory, CompanionEntity companion) {
		this(id, inventory, companion, new ContainerData() {
			@Override
			public int get(int index) {
				return switch (index) {
					case 0 -> companion.companionLevel();
					case 1 -> companion.xp();
					case 2 -> CompanionEntity.xpToNext(companion.companionLevel());
					case 3 -> companion.energy();
					default -> companion.burstEnergyCost();
				};
			}

			@Override
			public void set(int index, int value) {
			}

			@Override
			public int getCount() {
				return 5;
			}
		});
	}

	/** Client side, from the open-screen packet (it carries the companion's entity id). */
	public static CompanionMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
		CompanionEntity companion = inventory.player.level().getEntity(buf.readVarInt()) instanceof CompanionEntity c ? c : null;
		return new CompanionMenu(id, inventory, companion, new net.minecraft.world.inventory.SimpleContainerData(5));
	}

	private CompanionMenu(int id, Inventory inventory, @Nullable CompanionEntity companion, ContainerData data) {
		super(ModMenus.COMPANION.get(), id);
		this.companion = companion;
		this.data = data;
		Container equipment = new Equipment(companion);
		for (int i = 0; i < 4; i++) {
			EquipmentSlot slot = SLOTS[i];
			addSlot(new Slot(equipment, i, ARMOR_X, ARMOR_Y + i * ARMOR_STEP) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return companion != null && stack.canEquip(slot, companion);
				}

				@Override
				public int getMaxStackSize() {
					return 1;
				}
			});
		}
		addSlot(new Slot(equipment, 4, WEAPON_X, WEAPON_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return isWeapon(stack);
			}

			@Override
			public int getMaxStackSize() {
				return 1;
			}
		});
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(inventory, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(inventory, col, INV_X + col * 18, INV_Y + 58));
		}
		addDataSlots(data);
	}

	/** Anything that adds attack damage when held: swords, axes, tridents, modded weapons. */
	public static boolean isWeapon(ItemStack stack) {
		return !stack.isEmpty() && !stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE).isEmpty();
	}

	@Nullable
	public CompanionEntity companion() {
		return companion;
	}

	public int level() {
		return data.get(0);
	}

	public int xp() {
		return data.get(1);
	}

	public int xpToNext() {
		return data.get(2);
	}

	public int energy() {
		return data.get(3);
	}

	public int burstCost() {
		return data.get(4);
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (companion == null || !companion.isOwnedBy(player)) {
			return false;
		}
		switch (id) {
			case BUTTON_MODE -> companion.setMode(companion.mode().next());
			case BUTTON_BEHAVIOR -> companion.setBehavior(companion.behavior().next());
			case BUTTON_WEAPON -> companion.toggleSetting(CompanionEntity.SHOW_WEAPON);
			case BUTTON_SKILL -> companion.toggleSetting(CompanionEntity.SKILL_ON);
			case BUTTON_BURST -> companion.toggleSetting(CompanionEntity.BURST_ON);
			case BUTTON_PLAY -> {
				// Play as her: the menu closes and you become her.
				if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
					serverPlayer.closeContainer();
					com.freshorangeslices.enshining.play.PlayAs.start(serverPlayer, companion);
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		if (index < 5) {
			if (!moveItemStackTo(stack, 5, slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else {
			boolean moved = false;
			for (int i = 0; i < 5 && !moved; i++) {
				Slot target = slots.get(i);
				if (!target.hasItem() && target.mayPlace(stack)) {
					target.setByPlayer(stack.split(1));
					moved = true;
				}
			}
			if (!moved) {
				return ItemStack.EMPTY;
			}
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return copy;
	}

	@Override
	public boolean stillValid(Player player) {
		return companion != null && companion.isAlive() && companion.isOwnedBy(player) && companion.distanceToSqr(player) < 8 * 8;
	}

	/** Her equipment, seen as a 5-slot container; anything put in always drops if she dies. */
	private static class Equipment implements Container {
		@Nullable
		private final CompanionEntity companion;

		Equipment(@Nullable CompanionEntity companion) {
			this.companion = companion;
		}

		@Override
		public int getContainerSize() {
			return 5;
		}

		@Override
		public boolean isEmpty() {
			for (int i = 0; i < 5; i++) {
				if (!getItem(i).isEmpty()) {
					return false;
				}
			}
			return true;
		}

		@Override
		public ItemStack getItem(int index) {
			return companion == null ? ItemStack.EMPTY : companion.getItemBySlot(SLOTS[index]);
		}

		@Override
		public ItemStack removeItem(int index, int count) {
			ItemStack stack = getItem(index);
			if (stack.isEmpty()) {
				return ItemStack.EMPTY;
			}
			ItemStack taken = stack.split(count);
			setItem(index, stack);
			return taken;
		}

		@Override
		public ItemStack removeItemNoUpdate(int index) {
			ItemStack stack = getItem(index);
			setItem(index, ItemStack.EMPTY);
			return stack;
		}

		@Override
		public void setItem(int index, ItemStack stack) {
			if (companion != null) {
				companion.setItemSlot(SLOTS[index], stack);
				companion.setGuaranteedDrop(SLOTS[index]);
			}
		}

		@Override
		public void setChanged() {
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}

		@Override
		public void clearContent() {
			for (int i = 0; i < 5; i++) {
				setItem(i, ItemStack.EMPTY);
			}
		}
	}
}
