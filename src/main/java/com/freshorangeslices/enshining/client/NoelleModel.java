package com.freshorangeslices.enshining.client;

import com.freshorangeslices.enshining.character.noelle.NoelleEntity;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;

/** Noelle's model: the shared rig, plus her claymore growing during Sweeping Time. */
public class NoelleModel extends CompanionModel<NoelleEntity> {
	public static final float BURST_CLAYMORE_SCALE = 1.8F;

	public NoelleModel() {
		super("noelle");
	}

	@Override
	public void setCustomAnimations(NoelleEntity noelle, long instanceId, AnimationState<NoelleEntity> state) {
		super.setCustomAnimations(noelle, instanceId, state);
		CoreGeoBone weapon = getAnimationProcessor().getBone("weapon");
		if (weapon != null) {
			float scale = noelle.sweeping() ? BURST_CLAYMORE_SCALE : 1.0F;
			weapon.setScaleX(scale);
			weapon.setScaleY(scale);
			weapon.setScaleZ(scale);
		}
	}
}
