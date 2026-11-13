package com.freshorangeslices.enshining.client;

import javax.annotation.Nullable;

import com.freshorangeslices.enshining.core.CompanionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Every companion drawn the normal (cutout) way, which keeps overlapping cloth and armor sorting correctly, plus:
 * a "weapon_aura" bone drawn in its own see-through glowing pass while {@link CompanionEntity#weaponAuraActive()},
 * and a swapped-in weapon (her menu's weapon slot) drawn in her hand or hovering at "weapon_back".
 */
public class CompanionRenderer<T extends CompanionEntity> extends GeoEntityRenderer<T> {
	private static final String AURA = "weapon_aura";
	private static final int FULL_BRIGHT = 0xF000F0;
	private boolean auraPass;

	public CompanionRenderer(EntityRendererProvider.Context context, CompanionModel<T> model) {
		super(context, model);
		addRenderLayer(new GeoRenderLayer<>(this) {
			@Override
			public void render(PoseStack poseStack, T companion, BakedGeoModel model, RenderType renderType,
					MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
				if (!companion.weaponAuraActive() || model.getBone(AURA).isEmpty()) {
					return;
				}
				RenderType aura = RenderType.entityTranslucentEmissive(getTextureLocation(companion));
				auraPass = true;
				try {
					getRenderer().reRender(model, poseStack, bufferSource, companion, aura, bufferSource.getBuffer(aura), partialTick,
							FULL_BRIGHT, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
				} finally {
					auraPass = false;
				}
			}
		});
		addRenderLayer(new BlockAndItemGeoLayer<>(this) {
			@Nullable
			@Override
			protected ItemStack getStackForBone(GeoBone bone, T companion) {
				if (!companion.holdsSwappedWeapon() || !companion.setting(CompanionEntity.SHOW_WEAPON)) {
					return null;
				}
				return switch (bone.getName()) {
					case "right_hand" -> companion.showsWeaponInHand() ? companion.getMainHandItem() : null;
					case "weapon_back" -> companion.showBackWeapon() ? companion.getMainHandItem() : null;
					default -> null;
				};
			}

			@Override
			protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, T companion) {
				return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
			}

			@Override
			protected void renderStackForBone(PoseStack poseStack, GeoBone bone, ItemStack stack, T companion,
					MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
				poseStack.pushPose();
				if ("right_hand".equals(bone.getName())) {
					// Held like a player holds it: blade forward out of the fist.
					poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
					poseStack.translate(0.0, 0.1, 0.0);
				} else {
					// Hovering behind her: point down.
					poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
				}
				super.renderStackForBone(poseStack, bone, stack, companion, bufferSource, partialTick, packedLight, packedOverlay);
				poseStack.popPose();
			}
		});
	}

	private static boolean isOwnWeaponModel(GeoBone bone) {
		for (GeoBone b = bone; b != null; b = b.getParent()) {
			if ("weapon".equals(b.getName()) || "weapon_back".equals(b.getName())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight, int packedOverlay,
			float red, float green, float blue, float alpha) {
		// Each pass draws only its own cubes: the body skips the aura, the aura pass skips the body.
		if (AURA.equals(bone.getName()) != auraPass) {
			return;
		}
		// A swapped-in weapon replaces her own weapon model, in hand and hovering behind her.
		if (animatable != null && animatable.holdsSwappedWeapon() && isOwnWeaponModel(bone)) {
			return;
		}
		super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, red, green, blue, alpha);
	}
}
