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
import software.bernie.geckolib.core.animation.AnimationController;
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
		return bonesOf(current.animation());
	}

	/** Every bone an animation keys (cached). */
	private Set<String> bonesOf(Animation animation) {
		return rotatedBones.computeIfAbsent(animation, a -> {
			Set<String> names = new HashSet<>();
			for (BoneAnimation bone : a.boneAnimations()) {
				names.add(bone.boneName());
			}
			return names;
		});
	}

	private final Map<Animation, Set<String>> scaledBones = new IdentityHashMap<>();

	/** The bones the current animation sets the size of (scale keys), the only ones whose size it decides. */
	private Set<String> scaledBones(AnimationState<T> state) {
		AnimationProcessor.QueuedAnimation current = state.getController().getCurrentAnimation();
		if (current == null) {
			return Set.of();
		}
		return scaledBones.computeIfAbsent(current.animation(), animation -> {
			Set<String> names = new HashSet<>();
			for (BoneAnimation bone : animation.boneAnimations()) {
				if (keysScale(bone)) {
					names.add(bone.boneName());
				}
			}
			return names;
		});
	}

	/**
	 * Whether an animation has scale keys for this bone. Read by reflection: GeckoLib's keyframe types name a math
	 * class (mclib's IValue) that isn't on this mod's compile classpath, so they can't be named here.
	 */
	private static boolean keysScale(BoneAnimation bone) {
		try {
			Object stack = BoneAnimation.class.getMethod("scaleKeyFrames").invoke(bone);
			for (String axis : new String[] {"xKeyframes", "yKeyframes", "zKeyframes"}) {
				if (!((java.util.List<?>) stack.getClass().getMethod(axis).invoke(stack)).isEmpty()) {
					return true;
				}
			}
		} catch (ReflectiveOperationException | ClassCastException e) {
			return false;
		}
		return false;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void setCustomAnimations(T companion, long instanceId, AnimationState<T> state) {
		// GeckoLib hands this the state of the LAST controller it ran (the sitting layer, which is usually stopped):
		// everything below reads her main animation, so point it back at "main". Without this, nothing looked keyed,
		// and the vanilla limb swing and head turn went on top of every attack.
		var main = companion.getAnimatableInstanceCache().getManagerForId(instanceId).getAnimationControllers().get("main");
		if (main != null) {
			state.withController((AnimationController<T>) (Object) main);
		}
		// GeckoLib's head turn REPLACES the head's rotation with the look direction, which would wipe out head keys
		// from Blockbench. By this point GeckoLib has already written this frame's animation into the head (keyed, or
		// easing back to rest), so read it, let the look turn happen, then put the animation back: in a fight (and
		// for 3s after) the head follows the animation only; otherwise the look is added on top, limited so the
		// two together never twist her neck past what a person could.
		CoreGeoBone head = getAnimationProcessor().getBone("head");
		Set<String> animated = animatedBones(state);
		float animX = 0, animY = 0;
		// Only a keyed head carries the animation's turn: otherwise it still holds last frame's look (GeckoLib leaves
		// bones the model code moved alone), which would add onto itself every frame.
		if (head != null && animated.contains("head")) {
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
		weapons(companion, state, scaledBones(state));
		props(companion, instanceId, state.getPartialTick());
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
		EyeRig.apply(this, companion, time, state.getPartialTick(), expression(companion, time));   // experiments only
		flow(companion, state, time, swing, amount, animated);
		// Armor pieces of the rig stay hidden until armor drawing is in.
		for (CoreGeoBone bone : getAnimationProcessor().getRegisteredBones()) {
			if (bone.getName().startsWith("armor_")) {
				bone.setHidden(true);
			}
		}
	}

	/**
	 * Props (everything in the "props" group: her couch, her tea set...) belong to the animations that use them:
	 * each one shows only while a playing animation (any of her controllers: the sitting idle has its own) keys it
	 * or anything inside it, and appears and vanishes like her weapons (grows in over 0.2 s with a sparkle).
	 */
	private void props(T companion, long instanceId, float partialTick) {
		CoreGeoBone props = getAnimationProcessor().getBone("props");
		if (props == null) {
			return;
		}
		Set<String> keyed = new HashSet<>();
		Set<String> scaled = new HashSet<>();
		var manager = companion.getAnimatableInstanceCache().getManagerForId(instanceId);
		for (var controller : manager.getAnimationControllers().values()) {
			AnimationProcessor.QueuedAnimation current = controller.getCurrentAnimation();
			if (current == null || controller.getAnimationState() == AnimationController.State.STOPPED) {
				continue;
			}
			keyed.addAll(bonesOf(current.animation()));
			for (BoneAnimation bone : current.animation().boneAnimations()) {
				if (keysScale(bone)) {
					scaled.add(bone.boneName());
				}
			}
		}
		float time = companion.tickCount + partialTick;
		float dt = companion.propLastTime == 0 ? 1 : Mth.clamp(time - companion.propLastTime, 0, 5);
		companion.propLastTime = time;
		for (CoreGeoBone prop : props.getChildBones()) {
			boolean wanted = keyed.contains("props") || keyedWithin(prop, keyed);
			float in = Mth.approach(companion.propIn.getOrDefault(prop.getName(), 0.0F), wanted ? 1 : 0, dt / 4.0F);
			companion.propIn.put(prop.getName(), in);
			companion.propWanted.put(prop.getName(), wanted);
			prop.setHidden(in <= 0);
			if (in > 0) {
				materialise(prop, in, scaled.contains(prop.getName()));
			}
		}
	}

	private static boolean keyedWithin(CoreGeoBone bone, Set<String> keyed) {
		if (keyed.contains(bone.getName())) {
			return true;
		}
		for (CoreGeoBone child : bone.getChildBones()) {
			if (keyedWithin(child, keyed)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Her weapons appear and vanish by magic, like Genshin's: the claymore in her hand while she swings or casts
	 * (her walk and run were made without it, and carrying it through them drags the blade through the ground),
	 * and the one hovering behind her after a fight (if the model has "weapon_back"), which floats down into place
	 * and bobs. Each grows in over 0.15 s and shrinks away over 0.2 s (unless the animation itself scales it);
	 * the renderer scatters light over the blade while it does.
	 */
	private void weapons(T companion, AnimationState<T> state, Set<String> scaled) {
		float time = companion.tickCount + state.getPartialTick();
		float dt = time - companion.weaponLastTime;
		boolean snap = companion.weaponLastTime < 0 || dt < 0 || dt > 4.0F;
		if (dt != 0) {
			companion.weaponLastTime = time;
		}
		boolean shown = companion.setting(CompanionEntity.SHOW_WEAPON);
		// A weapon you gave her comes and goes exactly like her claymore (the renderer draws it instead of the
		// claymore's cubes, at "weapon_grip"); her burst glow, in the claymore's bone, overlays either.
		boolean hand = shown && companion.showsWeaponInHand();
		boolean back = shown && companion.showBackWeapon();
		// In her hand it's there at once (any grow-in reads as a delay at the start of a swing); the sparkle still
		// plays over it for a moment. It still fades away over 0.2 s when she's done.
		if (hand && !companion.handWeaponWanted && !snap) {
			companion.handWeaponSpark = 5.0F;
		}
		companion.handWeaponSpark = Math.max(0.0F, companion.handWeaponSpark - Math.max(0.0F, dt));
		companion.handWeaponWanted = hand;
		companion.backWeaponWanted = back;
		companion.handWeaponIn = hand || snap ? (hand ? 1 : 0) : Mth.approach(companion.handWeaponIn, 0, dt / 4.0F);
		companion.backWeaponIn = snap ? (back ? 1 : 0) : Mth.approach(companion.backWeaponIn, back ? 1 : 0, dt / (back ? 6.0F : 4.0F));

		CoreGeoBone weapon = getAnimationProcessor().getBone("weapon");
		if (weapon != null) {
			weapon.setHidden(companion.handWeaponIn <= 0);
			materialise(weapon, companion.handWeaponIn, scaled.contains("weapon"));
		}
		CoreGeoBone backBone = getAnimationProcessor().getBone("weapon_back");
		if (backBone != null) {
			// With a swapped weapon the bone stays (the weapon is drawn at it); the renderer skips the claymore copy.
			backBone.setHidden(companion.backWeaponIn <= 0);
			float in = companion.backWeaponIn;
			float drop = (1 - in) * (1 - in) * 4.0F;   // floats down the last few pixels into place
			backBone.setPosY(backBone.getInitialSnapshot().getOffsetY() + (float) Math.sin(time * 0.08) * 0.5F + drop);
			backBone.setRotZ(backBone.getInitialSnapshot().getRotZ() + (float) Math.sin(time * 0.05) * 0.03F);
			materialise(backBone, in, scaled.contains("weapon_back"));
		}
	}

	/**
	 * Scales a weapon bone by how far it has materialised (eased), on top of the animation's own scale if it sets
	 * one, else on top of its normal size. Never on top of last frame's: GeckoLib leaves a bone this code sized as it
	 * was, so a claymore caught fading out (between a combo and a skill, say) stayed shrunk to nothing.
	 */
	private static void materialise(CoreGeoBone bone, float in, boolean animated) {
		float s = in >= 1 ? 1 : in * in * (3 - 2 * in);
		float x = animated ? bone.getScaleX() : bone.getInitialSnapshot().getScaleX();
		float y = animated ? bone.getScaleY() : bone.getInitialSnapshot().getScaleY();
		float z = animated ? bone.getScaleZ() : bone.getInitialSnapshot().getScaleZ();
		bone.setScaleX(x * s);
		bone.setScaleY(y * s);
		bone.setScaleZ(z * s);
	}

	static final int OPEN = 0, HALF = 1, CLOSED = 2, HAPPY = 3;
	private static final String[] EYES = {"eyes_open", "eyes_half", "eyes_closed", "eyes_happy"};

	/**
	 * Eyes: one of "eyes_open", "eyes_half", "eyes_closed" and "eyes_happy" shows at a time (each a face overlay
	 * with its own pixels). Awake she blinks once every 3 to 7 seconds (different moments for different
	 * companions). Happy for 3 seconds when she levels up, is given a gift, her menu is opened, she wins a fight
	 * or you come back after being away (closed eyes if there's no happy drawing). Sitting: for the first 30
	 * seconds she drifts, dozing (half-closed) and flicking awake, dozing more and more; then a minute of full
	 * dozing, nodding off now and then; after that she sleeps (closed). Shut while hurt, and in the menu's armor
	 * pose. An expression not drawn yet falls back to open.
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
		int sat = companion.sittingTicks;
		int look = expression(companion, time);
		if (look == HAPPY && !drawn(bones[HAPPY])) {
			look = CLOSED;
		}
		if (look == HALF && !drawn(bones[HALF])) {
			look = sat > 0 ? OPEN : CLOSED;
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

	/**
	 * Which face she's making now (OPEN, HALF, CLOSED or HAPPY), shared by the painted eye sheets and the moving
	 * eyes ({@link EyeRig}): shut while hurt, dying or in the menu's armor pose; happy for a moment after good
	 * news; asleep, dozing or drifting off while sitting; otherwise blinking now and then.
	 */
	static int expression(CompanionEntity companion, float time) {
		int sat = companion.sittingTicks;
		int phase = ((int) time + companion.getId() * 37) % 160;   // an 8-second rhythm, different per companion
		if (companion.hurtTime > 0 || companion.deathTime > 0 || companion.menuEyesClosed) {
			return CLOSED;
		}
		if (companion.tickCount < companion.eyesHappyUntil) {
			return HAPPY;
		}
		if (sat > 1800) {
			return CLOSED;                                               // asleep
		}
		if (sat > 600) {
			return phase < 30 ? CLOSED : HALF;                           // dozing, nodding off now and then
		}
		if (sat > 0) {
			float drowsy = 0.2F + 0.6F * sat / 600.0F;                  // more of each 8 seconds spent dozing
			return phase < drowsy * 160 ? HALF : blink(companion.getId(), time, false);
		}
		boolean busy = companion.inCombat() || companion.getDeltaMovement().horizontalDistanceSqr() > 0.0004;
		return blink(companion.getId(), time, busy);
	}

	/**
	 * Where a blink is at: one blink per 90-tick stretch, starting somewhere in its first 60 ticks. Standing still
	 * it's slow and soft (7 ticks: half, shut, half); moving or fighting, quicker (4 ticks).
	 */
	private static int blink(int id, float time, boolean quick) {
		int stretch = Mth.floor(time / 90.0F);
		long seed = id * 341873128712L + stretch * 132897987541L;
		float t = time - (stretch * 90.0F + Math.floorMod(seed ^ (seed >>> 17), 60));
		int length = quick ? 4 : 7;
		int half = quick ? 1 : 2;
		if (t < 0 || t >= length) {
			return 0;
		}
		return t < half || t >= length - half ? 1 : 2;
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
	private void flow(T companion, AnimationState<T> state, float time, float swing, float amount, Set<String> animated) {
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
			// On top of the animation's own key if it has one, else on top of the rest pose: never on top of last
			// frame's sway (GeckoLib doesn't reset a bone this code moved, so that would add up into a spin).
			boolean keyed = animated.contains(bone.getName());
			float baseX = keyed ? bone.getRotX() : bone.getInitialSnapshot().getRotX();
			float baseZ = keyed ? bone.getRotZ() : bone.getInitialSnapshot().getRotZ();
			if (!keyed) {
				bone.setRotY(bone.getInitialSnapshot().getRotY());
			}
			bone.setRotX(baseX + Mth.clamp(x * more, -0.3F, 0.8F));
			bone.setRotZ(baseZ + Mth.clamp(z * more, -0.5F, 0.5F));
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
