package com.freshorangeslices.enshining.client;

import com.freshorangeslices.enshining.character.noelle.NoelleEntity;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;

/**
 * Noelle's model: the shared rig. During Sweeping Time her claymore stays its normal size; a "weapon_aura" bone (a
 * see-through magic blade modeled around it, if the model has one) shows instead, and her reach doubles in code.
 */
public class NoelleModel extends CompanionModel<NoelleEntity> {
	public NoelleModel() {
		super("noelle");
	}

	@Override
	public void setCustomAnimations(NoelleEntity noelle, long instanceId, AnimationState<NoelleEntity> state) {
		super.setCustomAnimations(noelle, instanceId, state);
		CoreGeoBone aura = getAnimationProcessor().getBone("weapon_aura");
		if (aura != null) {
			aura.setHidden(!(noelle.sweeping() && noelle.inCombat()));
		}
	}
}
