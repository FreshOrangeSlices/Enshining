package com.freshorangeslices.enshining.client;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.core.CompanionEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/** The shared rig's model: reads NAME's files, turns the head, and only shows the weapon in a fight (like Genshin). */
public class CompanionModel<T extends CompanionEntity> extends DefaultedEntityGeoModel<T> {
	public CompanionModel(String name) {
		super(new ResourceLocation(Enshining.MODID, name), true);
	}

	@Override
	public void setCustomAnimations(T companion, long instanceId, AnimationState<T> state) {
		super.setCustomAnimations(companion, instanceId, state);
		CoreGeoBone weapon = getAnimationProcessor().getBone("weapon");
		if (weapon != null) {
			weapon.setHidden(!companion.inCombat());
		}
		// Light cloth sway: skirt tiers and ribbon tails swing a little with each step and breathe when still,
		// each tier a beat behind the one above. Only bones an animation isn't moving.
		float time = companion.tickCount + state.getPartialTick();
		float swing = state.getLimbSwing();
		float amount = Math.min(1.0F, state.getLimbSwingAmount());
		sway("skirt", 0.0F, 0.035F, swing, amount, time);
		sway("skirt_mid", 0.6F, 0.03F, swing, amount, time);
		sway("skirt_hem", 1.2F, 0.03F, swing, amount, time);
		sway("bow_tail_left", 0.4F, 0.06F, swing, amount, time);
		sway("bow_tail_right", 0.9F, 0.06F, swing, amount, time);
	}

	private void sway(String name, float lag, float strength, float swing, float amount, float time) {
		CoreGeoBone bone = getAnimationProcessor().getBone(name);
		if (bone == null || bone.hasRotationChanged()) {
			return;
		}
		float walk = (float) Math.sin(swing * 0.6662F - lag) * amount * strength * 2.0F;
		float idle = (float) Math.sin(time * 0.06F - lag) * strength * 0.4F;
		bone.setRotX(bone.getInitialSnapshot().getRotX() + walk + idle);
		bone.setRotZ(bone.getInitialSnapshot().getRotZ() + (float) Math.cos(time * 0.045F - lag) * strength * 0.3F);
	}
}
