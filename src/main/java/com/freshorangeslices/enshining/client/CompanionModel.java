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
		vanillaLimbs(companion, state);

		// Cloth sway: skirt tiers and ribbon tails swing with each step, a beat behind the piece above, and breathe
		// when still. Heavier (bigger, slower) when running or in the air. Each piece has a hard limit, and the whole
		// chain added up stays small, so the skirt can never flip up.
		float time = companion.tickCount + state.getPartialTick();
		float swing = state.getLimbSwing();
		float amount = Math.min(1.0F, state.getLimbSwingAmount());
		boolean running = companion.isSprinting();
		boolean airborne = !companion.onGround() && !companion.isInWater();
		float heavy = running ? 1.6F : 1.0F;
		// In the air the skirt trails back a little (never up): a small lean, not a lift.
		float trail = airborne ? 0.05F : running ? 0.03F : 0.0F;
		sway("skirt", 0.0F, 0.03F * heavy, trail * 0.5F, 0.07F, swing, amount, time);
		sway("skirt_mid", 0.6F, 0.025F * heavy, trail * 0.3F, 0.05F, swing, amount, time);
		sway("skirt_hem", 1.2F, 0.025F * heavy, trail * 0.2F, 0.04F, swing, amount, time);
		sway("bow_tail_left", 0.4F, 0.06F * heavy, trail * 3.0F, 0.35F, swing, amount, time);
		sway("bow_tail_right", 0.9F, 0.06F * heavy, trail * 3.0F, 0.35F, swing, amount, time);
	}

	/**
	 * Swings a bone: with the steps (strength), a slow drift when still, plus a steady lean (trail), clamped to
	 * +-limit radians. Skirt limits add up to about 9 degrees for the hem, so it can't flip up.
	 */
	private void sway(String name, float lag, float strength, float trail, float limit, float swing, float amount, float time) {
		CoreGeoBone bone = getAnimationProcessor().getBone(name);
		if (bone == null || bone.hasRotationChanged()) {
			return;
		}
		float walk = (float) Math.sin(swing * 0.6662F - lag) * amount * strength * 2.0F;
		float idle = (float) Math.sin(time * 0.06F - lag) * strength * 0.4F;
		float x = clamp(walk + idle + trail, limit);
		float z = clamp((float) Math.cos(time * 0.045F - lag) * strength * 0.3F, limit * 0.5F);
		bone.setRotX(bone.getInitialSnapshot().getRotX() + x);
		bone.setRotZ(bone.getInitialSnapshot().getRotZ() + z);
	}

	/**
	 * Minecraft's own humanoid walk (the formula players, villagers and zombies use): legs and arms swing with how far
	 * and fast she's actually moving, and the arms bob a little when still. A fallback: any bone a real animation
	 * moves this frame (a Blockbench walk, run, attack...) is left to that animation.
	 */
	private void vanillaLimbs(T companion, AnimationState<T> state) {
		float swing = state.getLimbSwing() * 0.6662F;
		float amount = Math.min(1.0F, state.getLimbSwingAmount());
		float age = companion.tickCount + state.getPartialTick();
		float armBobZ = (float) Math.cos(age * 0.09F) * 0.05F + 0.05F;
		float armBobX = (float) Math.sin(age * 0.067F) * 0.05F;
		limb("right_leg", (float) Math.cos(swing) * 1.4F * amount, 0);
		limb("left_leg", (float) Math.cos(swing + Math.PI) * 1.4F * amount, 0);
		limb("right_arm", (float) Math.cos(swing + Math.PI) * amount + armBobX, armBobZ);
		limb("left_arm", (float) Math.cos(swing) * amount - armBobX, -armBobZ);
	}

	private void limb(String name, float x, float z) {
		CoreGeoBone bone = getAnimationProcessor().getBone(name);
		if (bone == null || bone.hasRotationChanged()) {
			return;
		}
		bone.setRotX(bone.getInitialSnapshot().getRotX() + x);
		bone.setRotZ(bone.getInitialSnapshot().getRotZ() + z);
	}

	private static float clamp(float value, float limit) {
		return Math.max(-limit, Math.min(limit, value));
	}
}
