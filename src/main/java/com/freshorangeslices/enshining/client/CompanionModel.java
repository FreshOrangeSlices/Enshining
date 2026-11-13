package com.freshorangeslices.enshining.client;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.core.CompanionEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/** The shared rig's model: reads NAME's files, turns the head, shows the weapon in hand or hovering behind her. */
public class CompanionModel<T extends CompanionEntity> extends DefaultedEntityGeoModel<T> {
	public CompanionModel(String name) {
		super(new ResourceLocation(Enshining.MODID, name), true);
	}

	/** Bones each animation keys (cached per animation). */
	private final Map<Animation, Set<String>> rotatedBones = new IdentityHashMap<>();

	/**
	 * The bones the playing animation moves. GeckoLib clears its own "this bone was animated" flags before this
	 * model code runs, so they can't be used; this reads the animation itself instead.
	 */
	private Set<String> animatedBones(AnimationState<T> state) {
		AnimationProcessor.QueuedAnimation current = state.getController().getCurrentAnimation();
		if (current == null) {
			return Set.of();
		}
		return rotatedBones.computeIfAbsent(current.animation(), animation -> {
			Set<String> names = new HashSet<>();
			for (BoneAnimation bone : animation.boneAnimations()) {
				names.add(bone.boneName());
			}
			return names;
		});
	}

	@Override
	public void setCustomAnimations(T companion, long instanceId, AnimationState<T> state) {
		// GeckoLib's head turn REPLACES the head's rotation with the look direction, which would wipe out head keys
		// from Blockbench. By this point GeckoLib has already written this frame's animation into the head (keyed, or
		// easing back to rest), so read it, let the look turn happen, then put the animation back: in a fight (and
		// for 3s after) the head follows the animation only; otherwise the look is added on top, limited so the
		// two together never twist her neck past what a person could.
		CoreGeoBone head = getAnimationProcessor().getBone("head");
		float animX = 0, animY = 0;
		if (head != null) {
			animX = head.getRotX() - head.getInitialSnapshot().getRotX();
			animY = head.getRotY() - head.getInitialSnapshot().getRotY();
		}
		super.setCustomAnimations(companion, instanceId, state);
		if (head != null) {
			float baseX = head.getInitialSnapshot().getRotX();
			float baseY = head.getInitialSnapshot().getRotY();
			// The look eases out over half a second when a fight starts and back in after, instead of snapping.
			float look = companion.lookBlend(state.getPartialTick());
			float lookX = (head.getRotX() - baseX) * look;
			float lookY = (head.getRotY() - baseY) * look;
			// The animation's own head turn always plays in full; the look is added only up to a natural neck limit.
			head.setRotX(baseX + animX + limitLook(lookX, animX, 0.7F));
			head.setRotY(baseY + animY + limitLook(lookY, animY, 1.3F));
		}
		CoreGeoBone weapon = getAnimationProcessor().getBone("weapon");
		if (weapon != null) {
			// Out only while she's swinging, holding a hit's pose, or casting (like Genshin): her walk and run were
			// made without the claymore, and carrying it through them drags the blade through the ground.
			weapon.setHidden(!companion.playingMove());
		}
		// The claymore hovering behind her after a fight (a separate bone, if the model has one), bobbing gently.
		CoreGeoBone back = getAnimationProcessor().getBone("weapon_back");
		if (back != null) {
			back.setHidden(!companion.showBackWeapon());
			float t = companion.tickCount + state.getPartialTick();
			back.setPosY(back.getInitialSnapshot().getOffsetY() + (float) Math.sin(t * 0.08) * 0.5F);
			back.setRotZ(back.getInitialSnapshot().getRotZ() + (float) Math.sin(t * 0.05) * 0.03F);
		}
		Set<String> animated = animatedBones(state);
		vanillaLimbs(companion, state, animated);

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
		sway("skirt", 0.0F, 0.03F * heavy, trail * 0.5F, 0.07F, swing, amount, time, animated);
		// The front panel hangs from the same waist as the skirt, so it sways with it (else they cut through each other).
		sway("SkirtFront", 0.0F, 0.03F * heavy, trail * 0.5F, 0.07F, swing, amount, time, animated);
		sway("skirt_mid", 0.6F, 0.025F * heavy, trail * 0.3F, 0.05F, swing, amount, time, animated);
		sway("skirt_hem", 1.2F, 0.025F * heavy, trail * 0.2F, 0.04F, swing, amount, time, animated);
		sway("bow_tail_left", 0.4F, 0.06F * heavy, trail * 3.0F, 0.35F, swing, amount, time, animated);
		sway("bow_tail_right", 0.9F, 0.06F * heavy, trail * 3.0F, 0.35F, swing, amount, time, animated);
		skirtFollowsLegs();
		flow(companion, state, time, swing, amount);
	}

	/**
	 * A skirt rebuilt with "skirt_front" / "skirt_back" bones: the front panel is pushed forward by whichever thigh
	 * lifts forward, the back one by whichever kicks back (about half as far), so legs never poke through.
	 * Rotation here reads like Blockbench: negative X = forward.
	 */
	private void skirtFollowsLegs() {
		CoreGeoBone right = getAnimationProcessor().getBone("right_leg");
		CoreGeoBone left = getAnimationProcessor().getBone("left_leg");
		if (right == null || left == null) {
			return;
		}
		float r = right.getRotX() - right.getInitialSnapshot().getRotX();
		float l = left.getRotX() - left.getInitialSnapshot().getRotX();
		CoreGeoBone front = getAnimationProcessor().getBone("skirt_front");
		if (front != null) {
			front.setRotX(front.getRotX() + 0.55F * Math.min(0.0F, Math.min(r, l)));
		}
		CoreGeoBone back = getAnimationProcessor().getBone("skirt_back");
		if (back != null) {
			back.setRotX(back.getRotX() + 0.55F * Math.max(0.0F, Math.max(r, l)));
		}
	}

	/**
	 * Secondary motion for every bone named "flow_...": hair strands, ribbons, capes, tassels. Added on top of the
	 * animation (so keys still work), it trails behind when she moves, swings with her steps, lifts when she falls,
	 * swings out when she turns, and drifts a little when still. A flow bone inside another flow bone moves a beat
	 * later and a bit more, so chains (flow_ribbon > flow_ribbon_tip) ripple.
	 */
	private void flow(T companion, AnimationState<T> state, float time, float swing, float amount) {
		float fall = Mth.clamp((float) -companion.getDeltaMovement().y * 1.2F, -0.15F, 0.4F);
		float turn = Mth.clamp(Mth.wrapDegrees(companion.yBodyRot - companion.yBodyRotO) * -0.02F, -0.35F, 0.35F);
		float speed = Mth.clamp(amount * (companion.isSprinting() ? 0.35F : 0.18F), 0.0F, 0.4F);
		for (CoreGeoBone bone : getAnimationProcessor().getRegisteredBones()) {
			if (!bone.getName().startsWith("flow_")) {
				continue;
			}
			int depth = 0;
			for (CoreGeoBone parent = bone.getParent(); parent != null; parent = parent.getParent()) {
				if (parent.getName().startsWith("flow_")) {
					depth++;
				}
			}
			float phase = (bone.getName().hashCode() & 0xFF) / 255.0F * Mth.TWO_PI;
			float lag = depth * 0.7F;
			float more = 1.0F + depth * 0.35F;
			float x = speed + fall
					+ (float) Math.sin(swing * 0.6662F - lag) * amount * 0.06F
					+ (float) Math.sin(time * 0.07F + phase - lag) * 0.035F;
			float z = turn + (float) Math.sin(time * 0.05F + phase - lag) * 0.03F;
			bone.setRotX(bone.getRotX() + Mth.clamp(x * more, -0.3F, 0.8F));
			bone.setRotZ(bone.getRotZ() + Mth.clamp(z * more, -0.5F, 0.5F));
		}
	}

	/**
	 * Swings a bone: with the steps (strength), a slow drift when still, plus a steady lean (trail), clamped to
	 * +-limit radians. Skirt limits add up to about 9 degrees for the hem, so it can't flip up.
	 */
	private void sway(String name, float lag, float strength, float trail, float limit, float swing, float amount, float time,
			Set<String> animated) {
		CoreGeoBone bone = getAnimationProcessor().getBone(name);
		if (bone == null || animated.contains(name)) {
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
	private void vanillaLimbs(T companion, AnimationState<T> state, Set<String> animated) {
		float swing = state.getLimbSwing() * 0.6662F;
		float amount = Math.min(1.0F, state.getLimbSwingAmount());
		float age = companion.tickCount + state.getPartialTick();
		float armBobZ = (float) Math.cos(age * 0.09F) * 0.05F + 0.05F;
		float armBobX = (float) Math.sin(age * 0.067F) * 0.05F;
		limb("right_leg", (float) Math.cos(swing) * 1.4F * amount, 0, animated);
		limb("left_leg", (float) Math.cos(swing + Math.PI) * 1.4F * amount, 0, animated);
		limb("right_arm", (float) Math.cos(swing + Math.PI) * amount + armBobX, armBobZ, animated);
		limb("left_arm", (float) Math.cos(swing) * amount - armBobX, -armBobZ, animated);
	}

	private void limb(String name, float x, float z, Set<String> animated) {
		CoreGeoBone bone = getAnimationProcessor().getBone(name);
		if (bone == null || animated.contains(name)) {
			return;
		}
		bone.setRotX(bone.getInitialSnapshot().getRotX() + x);
		bone.setRotZ(bone.getInitialSnapshot().getRotZ() + z);
	}

	/** The look turn, cut so animation + look stays within +-limit (it never pushes the other way). */
	private static float limitLook(float look, float anim, float limit) {
		return Mth.clamp(look, Math.min(0.0F, -limit - anim), Math.max(0.0F, limit - anim));
	}

	private static float clamp(float value, float limit) {
		return Math.max(-limit, Math.min(limit, value));
	}
}
