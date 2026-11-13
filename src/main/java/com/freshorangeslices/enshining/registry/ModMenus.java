package com.freshorangeslices.enshining.registry;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.core.CompanionMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
	public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, Enshining.MODID);

	public static final RegistryObject<MenuType<CompanionMenu>> COMPANION = MENUS.register("companion",
			() -> IForgeMenuType.create(CompanionMenu::fromNetwork));

	private ModMenus() {
	}
}
