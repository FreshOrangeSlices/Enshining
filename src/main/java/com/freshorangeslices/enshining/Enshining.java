package com.freshorangeslices.enshining;

import com.freshorangeslices.enshining.core.CompanionEvents;
import com.freshorangeslices.enshining.registry.ModEntities;
import com.freshorangeslices.enshining.registry.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Enshining: elemental companions. The companion system lives in {@code core}, each character in {@code character}. */
@Mod(Enshining.MODID)
public final class Enshining {
	public static final String MODID = "enshining";

	public Enshining() {
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		ModEntities.ENTITIES.register(modBus);
		ModItems.ITEMS.register(modBus);
		modBus.addListener(ModEntities::attributes);
		modBus.addListener(ModEntities::spawnPlacements);
		modBus.addListener(ModItems::creativeTabs);
		MinecraftForge.EVENT_BUS.register(CompanionEvents.class);
	}
}
