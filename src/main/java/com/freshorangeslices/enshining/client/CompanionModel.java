package com.freshorangeslices.enshining.client;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.core.CompanionEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;
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
			weapon.setHidden(!companion.showsWeaponInHand() || companion.holdsSwappedWeapon() || !companion.setting(CompanionEntity.SHOW_WEAPON));
		}
		// The claymore hovering behind her after a fight (a separate bone, if the model has one), bobbing gently.
		CoreGeoBone back = getAnimationProcessor().getBone("weapon_back");
		if (back != null) {
			// With a swapped weapon the bone stays (the weapon is drawn at it); the renderer skips the claymore copy.
			back.setHidden(!companion.showBackWeapon() || !companion.setting(CompanionEntity.SHOW_WEAPON));
			float t = companion.tickCount + state.getPartialTick();
			back.setPosY(back.getInitialSnapshot().getOffsetY() + (float) Math.sin(t * 0.08) * 0.5F);
			back.setRotZ(back.getInitialSnapshot().getRotZ() + (float) Math.sin(t * 0.05) * 0.03F);
		}
		Set<String> animated = animatedBones(state);
		vanillaLimbs(companion, state, animated);

		// The whole skirt sways a little with each step and breathes when still, heavier when running or in the air
		// (where it trails back a little, never up). Hard limit, so it can never flip up.
		float time = companion.tickCount + state.getPartialTick();
		float swing = state.getLimbSwing();
		float amount = Math.min(1.0F, state.getLimbSwingAmount());
		boolean running = companion.isSprinting();
		boolean airborne = !companion.onGround() && !companion.isInWater();
		float heavy = running ? 1.6F : 1.0F;
		float trail = airborne ? 0.05F : running ? 0.03F : 0.0F;
		sway("skirt", 0.0F, 0.03F * heavy, trail * 0.5F, 0.07F, swing, amount, time, animated);
		skirtRing(companion, time, state.getPartialTick());
		eyes(companion, time);
		flow(companion, state, time, swing, amount);
		// Armor pieces of the rig stay hidden until armor drawing is in.
		for (CoreGeoBone bone : getAnimationProcessor().getRegisteredBones()) {
			if (bone.getName().startsWith("armor_")) {
				bone.setHidden(true);
			}
		}
	}

	private static final int OPEN = 0, HALF = 1, CLOSED = 2, HAPPY = 3;
	private static final String[] EYES = {"eyes_open", "eyes_half", "eyes_closed", "eyes_happy"};

	/**
	 * Eyes: one of "eyes_open", "eyes_half", "eyes_closed" and "eyes_happy" shows at a time (each a face overlay
	 * with its own pixels). Awake she blinks once every 3 to 7 seconds (different moments for different
	 * companions). Smiling (happy eyes) for 3 seconds when she levels up, is given a gift or her menu is opened.
	 * Sitting for half a minute she dozes (half-closed, now and then nodding off); after two minutes, or soon at
	 * night, she sleeps (closed). Eyes shut while hurt. Any expression not drawn yet falls back to open.
	 */
	private void eyes(T companion, float time) {
		CoreGeoBone[] bones = new CoreGeoBone[EYES.length];
		boolean any = false;
		for (int i = 0; i < EYES.length; i++) {
			bones[i] = getAnimationProcessor().getBone(EYES[i]);
			any |= bones[i] != null;
		}
		if (!any) {
			return;
		}
		long day = companion.level().getDayTime() % 24000L;
		boolean night = day > 13000L && day < 23000L;
		int sat = companion.sittingTicks;
		int look;
		if (companion.hurtTime > 0 || companion.deathTime > 0) {
			look = CLOSED;
		} else if (companion.tickCount < companion.eyesHappyUntil) {
			look = HAPPY;
		} else if (sat > 2400 || (night && sat > 200)) {
			look = CLOSED;                                   // asleep
		} else if (sat > 600) {
			look = ((int) time + companion.getId() * 37) % 160 < 30 ? CLOSED : HALF;   // dozing, nodding off
		} else {
			look = blink(companion.getId(), time);
		}
		if (look == HALF && !drawn(bones[HALF])) {
			look = sat > 600 ? OPEN : CLOSED;
		}
		if (look != OPEN && !drawn(bones[look])) {
			look = OPEN;
		}
		for (int i = 0; i < EYES.length; i++) {
			if (bones[i] != null) {
				bones[i].setHidden(i != look);
			}
		}
	}

	/** Where a blink is at: one blink per 90-tick stretch, starting somewhere in its first 60 ticks; 7 ticks long. */
	private static int blink(int id, float time) {
		int stretch = Mth.floor(time / 90.0F);
		long seed = id * 341873128712L + stretch * 132897987541L;
		float t = time - (stretch * 90.0F + Math.floorMod(seed ^ (seed >>> 17), 60));
		if (t < 0 || t >= 7) {
			return 0;
		}
		return t < 2 || t >= 5 ? 1 : 2;
	}

	/** Whether a bone, or anything under it, has cubes to show. */
	private static boolean drawn(CoreGeoBone bone) {
		if (!(bone instanceof GeoBone geo)) {
			return false;
		}
		if (!geo.getCubes().isEmpty()) {
			return true;
		}
		for (GeoBone child : geo.getChildBones()) {
			if (drawn(child)) {
				return true;
			}
		}
		return false;
	}

	/** How much of a leg's swing the skirt takes, and the most the skirt flares out (radians, 12 degrees). */
	private static final float SKIRT_FOLLOW = 0.55F, SKIRT_MAX_FLARE = 0.21F;

	/**
	 * The skirt as one connected ring (drawn by {@link CompanionRenderer}).
	 * Legs: the front swings with whichever leg steps forward, the back with whichever kicks back (a leg's X rotation
	 * moves its foot the same way the same X rotation moves a skirt column's hem, so a column takes a share of it).
	 * Weight, as springs that overshoot a little and settle: the hem trails behind her when she moves and swings
	 * past when she stops; it flares out when she spins or falls and bounces when she lands; and it lags behind a
	 * turn or a spin (from her body turning or from the animation itself), then catches up.
	 */
	private void skirtRing(T companion, float time, float partialTick) {
		CoreGeoBone right = getAnimationProcessor().getBone("right_leg");
		CoreGeoBone left = getAnimationProcessor().getBone("left_leg");
		float r = right == null ? 0 : right.getRotX() - right.getInitialSnapshot().getRotX();
		float l = left == null ? 0 : left.getRotX() - left.getInitialSnapshot().getRotX();
		companion.skirtFront = SKIRT_FOLLOW * Math.max(0.0F, Math.max(r, l));   // positive X: foot (and hem) to the front
		companion.skirtBack = SKIRT_FOLLOW * Math.min(0.0F, Math.min(r, l));    // negative X: foot (and hem) to the back

		CoreGeoBone root = getAnimationProcessor().getBone("root");
		CoreGeoBone body = getAnimationProcessor().getBone("body");
		float spin = (root == null ? 0 : root.getRotY()) + (body == null ? 0 : body.getRotY());
		float yaw = Mth.rotLerp(partialTick, companion.yBodyRotO, companion.yBodyRot) * Mth.DEG_TO_RAD;
		double x = Mth.lerp(partialTick, companion.xo, companion.getX());
		double y = Mth.lerp(partialTick, companion.yo, companion.getY());
		double z = Mth.lerp(partialTick, companion.zo, companion.getZ());
		float dt = time - companion.skirtLastTime;
		if (companion.skirtLastTime < 0 || dt > 4.0F || dt < 0) {
			for (float[] s : new float[][] {companion.skirtLean, companion.skirtFlare, companion.skirtTwist}) {
				s[0] = s[1] = 0;
			}
		} else if (dt > 0) {
			float vx = (float) (x - companion.skirtLastX) / dt;   // blocks per tick
			float vy = (float) (y - companion.skirtLastY) / dt;
			float vz = (float) (z - companion.skirtLastZ) / dt;
			float forward = -vx * Mth.sin(yaw) + vz * Mth.cos(yaw);
			float turn = wrapRadians(yaw - companion.skirtLastYaw) / dt;       // her body turning (radians per tick)
			float spinRate = wrapRadians(spin - companion.skirtLastSpin) / dt;   // a spin in the animation
			// Turning by +yaw turns the model the other way round its Y axis.
			float modelSpin = spinRate - turn;
			boolean grounded = companion.onGround() || companion.isInWater() || companion.isPassenger();

			float lean = Mth.clamp(-0.8F * forward, -0.26F, 0.26F);   // moving forward: the hem trails back (+Z)
			float spinSpeed = Math.abs(spinRate) + Math.abs(turn);
			float flare = SKIRT_MAX_FLARE * Mth.clamp((spinSpeed - 0.08F) / 0.35F, 0.0F, 1.0F);
			if (!grounded && vy < -0.08F) {   // falling: the air lifts it
				flare = Math.max(flare, SKIRT_MAX_FLARE * Mth.clamp((-vy - 0.08F) / 0.35F, 0.0F, 1.0F));
			}
			float twist = Mth.clamp(-0.5F * modelSpin, -0.26F, 0.26F);   // the hem lags behind the spin

			int steps = Math.max(1, Mth.ceil(dt / 0.25F));
			float h = dt / steps;
			for (int i = 0; i < steps; i++) {
				spring(companion.skirtLean, lean, 0.45F, 0.35F, h);
				spring(companion.skirtFlare, flare, 0.6F, 0.45F, h);
				spring(companion.skirtTwist, twist, 0.4F, 0.3F, h);
			}
			companion.skirtLean[0] = Mth.clamp(companion.skirtLean[0], -0.3F, 0.3F);
			companion.skirtFlare[0] = Mth.clamp(companion.skirtFlare[0], -0.03F, SKIRT_MAX_FLARE);
			companion.skirtTwist[0] = Mth.clamp(companion.skirtTwist[0], -0.26F, 0.26F);
		}
		if (dt != 0) {
			companion.skirtLastTime = time;
			companion.skirtLastSpin = spin;
			companion.skirtLastYaw = yaw;
			companion.skirtLastX = x;
			companion.skirtLastY = y;
			companion.skirtLastZ = z;
		}
	}

	/** One step of a damped spring {angle, speed} pulled toward a target (omega: stiffness per tick, zeta: damping). */
	private static void spring(float[] s, float target, float omega, float zeta, float h) {
		float accel = omega * omega * (target - s[0]) - 2.0F * zeta * omega * s[1];
		s[1] += accel * h;
		s[0] += s[1] * h;
	}

	private static float wrapRadians(float angle) {
		return angle - Mth.TWO_PI * Math.round(angle / Mth.TWO_PI);
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
