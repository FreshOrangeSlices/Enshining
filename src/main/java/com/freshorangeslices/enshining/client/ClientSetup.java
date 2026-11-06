package com.freshorangeslices.enshining.client;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.registry.ModEntities;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderers. Each companion reads three files named after it: geo/entity/NAME.geo.json (the model),
 * textures/entity/NAME.png and animations/entity/NAME.animation.json.
 */
@Mod.EventBusSubscriber(modid = Enshining.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
	private ClientSetup() {
	}

	@SubscribeEvent
	public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerEntityRenderer(ModEntities.NOELLE.get(),
				context -> new GeoEntityRenderer<>(context, new DefaultedEntityGeoModel<>(new ResourceLocation(Enshining.MODID, "noelle"), true)));
	}
}
