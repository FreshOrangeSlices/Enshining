package com.freshorangeslices.enshining.registry;

import com.freshorangeslices.enshining.Enshining;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
	public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Enshining.MODID);

	public static final RegistryObject<Item> NOELLE_SPAWN_EGG = ITEMS.register("noelle_spawn_egg",
			() -> new ForgeSpawnEggItem(ModEntities.NOELLE, 0x2A2A34, 0xD6AA40, new Item.Properties()));

	private ModItems() {
	}

	public static void creativeTabs(BuildCreativeModeTabContentsEvent event) {
		if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
			event.accept(NOELLE_SPAWN_EGG);
		}
	}
}
