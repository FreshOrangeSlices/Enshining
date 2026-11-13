package com.freshorangeslices.enshining.client;

import com.freshorangeslices.enshining.character.noelle.NoelleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Noelle drawn the normal (cutout) way, which keeps overlapping cloth and armor sorting correctly. A "weapon_aura" bone,
 * if the model has one, is left out of that pass and drawn in a second, see-through and glowing pass during Sweeping
 * Time only: the magic blade around her claymore.
 */
public class NoelleRenderer extends GeoEntityRenderer<NoelleEntity> {
	private static final String AURA = "weapon_aura";
	private static final int FULL_BRIGHT = 0xF000F0;
	private boolean auraPass;

	public NoelleRenderer(EntityRendererProvider.Context context) {
		super(context, new NoelleModel());
		addRenderLayer(new GeoRenderLayer<>(this) {
			@Override
			public void render(PoseStack poseStack, NoelleEntity noelle, BakedGeoModel model, RenderType renderType,
					MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
				if (!noelle.sweeping() || !noelle.inCombat() || model.getBone(AURA).isEmpty()) {
					return;
				}
				RenderType aura = RenderType.entityTranslucentEmissive(getTextureLocation(noelle));
				auraPass = true;
				try {
					getRenderer().reRender(model, poseStack, bufferSource, noelle, aura, bufferSource.getBuffer(aura), partialTick,
							FULL_BRIGHT, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
				} finally {
					auraPass = false;
				}
			}
		});
	}

	@Override
	public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight, int packedOverlay,
			float red, float green, float blue, float alpha) {
		// Each pass draws only its own cubes: the body skips the aura, the aura pass skips the body.
		if (AURA.equals(bone.getName()) != auraPass) {
			return;
		}
		super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, red, green, blue, alpha);
	}
}
