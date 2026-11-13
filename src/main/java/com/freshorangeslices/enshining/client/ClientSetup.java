package com.freshorangeslices.enshining.client;

import javax.annotation.Nullable;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.character.noelle.NoelleEntity;
import com.freshorangeslices.enshining.registry.ModEntities;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
				context -> new GeoEntityRenderer<>(context, new NoelleModel()) {
					// Translucent, so see-through texture pixels (like a magic blade overlay) blend instead of cutting out.
					@Override
					public RenderType getRenderType(NoelleEntity animatable, ResourceLocation texture,
							@Nullable MultiBufferSource bufferSource, float partialTick) {
						return RenderType.entityTranslucent(texture);
					}
				});
	}
}
