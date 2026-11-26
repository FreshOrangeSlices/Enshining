package com.freshorangeslices.enshining.core;

import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.Mob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.state.BoneSnapshot;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Everything every companion shares: befriending with a gift, Follow / Wander / Stay, levels from kills, an elemental
 * skill on a cooldown and a burst charged by hits, and the shared animations (idle, walk, sit, attack). A character
 * only says what makes it itself: its element, gift, stats, and what its skill and burst do.
 */
public abstract class CompanionEntity extends TamableAnimal implements GeoEntity {
	public static final int MAX_LEVEL = 40;
	public static final int MAX_ENERGY = 100;

	private static final EntityDataAccessor<Integer> MODE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> LEVEL = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	/** The move playing now (0 none, 1 skill, 2 burst), synced so every client animates it. */
	private static final EntityDataAccessor<Integer> ACTION = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	/** In a fight (a target, or hit or hitting in the last 3 seconds): synced, so the weapon only shows then, like in Genshin. */
	private static final EntityDataAccessor<Boolean> COMBAT = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
	/** Menu settings, as bits (see SHOW_ARMOR...), and her behavior. */
	private static final EntityDataAccessor<Integer> SETTINGS = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> BEHAVIOR = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	public static final int SHOW_ARMOR = 1, SHOW_WEAPON = 2, SKILL_ON = 4, BURST_ON = 8;
	private static final int DEFAULT_SETTINGS = SHOW_ARMOR | SHOW_WEAPON | SKILL_ON | BURST_ON;
	private static final UUID WEAPON_SWAP = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000004");
	/** The dash at the start of a run: 1.35x speed for 0.5 s, only after 2 s of walking or standing. */
	private static final UUID DASH_SPEED = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000007");
	private static final int DASH_TICKS = 10, DASH_AFTER_CALM = 40;
	private int dashTicks, calmTicks = DASH_AFTER_CALM;
	/** Half speed while her burst plays. */
	private static final UUID BURST_SLOW = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000005");
	/** A combo hit's extra damage (see {@link #comboBonusDamage}), on only while that hit lands. */
	private static final UUID COMBO_BONUS = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000006");
	/** The combo, packed: (count of hits started << 3) | phase. Phase 0 = none, 1..n = combo hit n, 7 = the ending. */
	private static final EntityDataAccessor<Integer> COMBO = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	/** The first stretch of a run: the quick dash (Genshin's sprint start) before she settles into a jog. */
	private static final EntityDataAccessor<Boolean> DASHING = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
	private static final int COMBO_END_PHASE = 7;
	/** The charged attack's stage (0 none, 1 wind-up, 2 spin, 3 finish), for the clients' animation. */
	private static final EntityDataAccessor<Integer> CHARGE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	private static final int CHARGE_START = 1, CHARGE_SPIN = 2, CHARGE_FINISH = 3;
	/**
	 * Off-field: in the party of someone playing as one of you, but not the one they are right now (Genshin's
	 * party): hidden, untouchable, no AI, carried along with the one they play. Never saved: after a reload she's
	 * simply back.
	 */
	private static final EntityDataAccessor<Boolean> OFF_FIELD = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);
	/** For the E/Q display while you play as her: her skill cooldown (ticks) << 8 | her energy (capped at 255). */
	private static final EntityDataAccessor<Integer> ABILITIES = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	/** Slow, like sneaking, while she spins. */
	private static final UUID SPIN_SLOW = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000008");
	/** How long (ticks) she lingers on a combo hit's last frame waiting for the next one before the combo resets. */
	private static final int COMBO_WINDOW_TICKS = 20;
	/** Played as: how long she holds a hit's pose for your next click (4 s). */
	private static final int PLAY_COMBO_WINDOW_TICKS = 80;
	/** The charged finish blends in over this many ticks (its first frame doesn't match the wind-up's or the spin's end). */
	private static final int FINISH_BLEND = 4;
	public static final int ACTION_NONE = 0;
	public static final int ACTION_SKILL = 1;
	public static final int ACTION_BURST = 2;

	private static final UUID LEVEL_HEALTH = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000001");
	private static final UUID LEVEL_ATTACK = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000002");

	protected static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.companion.idle");
	protected static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.companion.walk");
	protected static final RawAnimation SIT = RawAnimation.begin().thenLoop("animation.companion.sit");
	protected static final RawAnimation RUN = RawAnimation.begin().thenLoop("animation.companion.run");
	/** Running after the dash; characters without one keep running with RUN. */
	protected static final RawAnimation JOG = RawAnimation.begin().thenLoop("animation.companion.jog");
	/** Sitting, layered over SIT (its own controller): her sitting idle, the one that can bring out her props. */
	protected static final RawAnimation SIT_IDLE = RawAnimation.begin().thenLoop("animation.companion.sit_idle");
	protected static final RawAnimation SIT_IDLE_INTRO = RawAnimation.begin().thenPlayAndHold("animation.companion.sit_idle_intro");
	private static final int SIT_BLEND = 6;
	private boolean sitIntroPlayed;   // client side: her sitting idle's start has played this time she sat
	protected static final RawAnimation JUMP = RawAnimation.begin().thenLoop("animation.companion.jump");
	protected static final RawAnimation SWIM = RawAnimation.begin().thenLoop("animation.companion.swim");
	protected static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.companion.attack");

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
	private int xp;
	private int skillCooldown;
	private int energy;
	private int actionTicks;
	private int combatTicks;
	private boolean wasSwinging; // client side, to restart the attack animation on each new swing
	private boolean playingMove; // client side: an attack, skill or burst animation is playing
	private int actionShown;     // client side: the skill or burst the animation was last started for
	// Combo, server side
	private int comboNext;        // which hit comes next (0-based)
	private int comboHitIndex;    // which hit is playing (0-based; the charged attack is one past the last hit)
	private int combosFinished;   // combos played to their last hit, for the charged attack's turn
	private int chargeStage;      // server side: where the charged attack is (CHARGE_*), and for how long
	private int chargeTicks;
	private int spinLoops;
	private int chargeShown;      // client side: the stage the animation was last set for
	private int comboCount;       // hits started, so clients can tell a new hit from the same one
	private int comboPieceTicks;  // ticks left in the hit being played
	private int comboWindowTicks; // ticks left to continue the combo after a hit
	private int comboEndTicks;    // ticks left in the ending
	private int[] comboImpacts = new int[0]; // when (ticks into the hit) each impact lands
	private int comboImpactIndex; // impacts already landed
	private int comboElapsed;     // ticks into the current hit
	private int comboCancelTicks; // ticks until the next hit may cancel this one's recovery
	@Nullable
	private LivingEntity comboTarget;
	private int comboSeen = -1;   // client side: the last hit this client started playing
	private boolean comboShowing; // client side: a combo animation was showing last frame
	private double comboTravelled; // blocks moved since the current hit started
	@Nullable
	private net.minecraft.world.phys.Vec3 comboLastPos;
	private float comboYaw;
	private float comboAimYaw;       // the way she faces for the whole of a hit (turning mid-swing spins the animation)

	protected CompanionEntity(EntityType<? extends TamableAnimal> type, Level level) {
		super(type, level);
	}

	// ---- What each character fills in -------------------------------------------------------------------------

	public abstract Element element();

	/** The item a wild one is befriended with (and an owner heals it with). */
	public abstract Item gift();

	/** Ticks between elemental skills. */
	protected abstract int skillCooldownTicks();

	/** Energy the burst needs (hits give 10, a skill 6, up to {@link #MAX_ENERGY}). */
	protected abstract int burstCost();

	/** The elemental skill, used in a fight when it's off cooldown. */
	public abstract void useSkill(@Nullable LivingEntity target);

	/** The burst, used in a fight once energy is full enough. */
	public abstract void useBurst(@Nullable LivingEntity target);

	/** How far her melee reaches compared to normal (Noelle's burst doubles it). */
	public double meleeReachScale() {
		return 1.0;
	}

	/** Called after each landed melee hit, for effects that ride on hits (heals, sweeps). */
	protected void onHit(LivingEntity target) {
	}

	/** Called every server tick, for timers a skill or burst started. */
	protected void tickAbilities() {
	}

	/**
	 * One hit of a melee combo: its animation, its length and when its impact lands, both in seconds as animated in
	 * Blockbench (before {@link #comboSpeed()}).
	 */
	/**
	 * One hit of a melee combo: its animation, its length, when its impact(s) land, and when its motion ends (its last
	 * keyframe; after that it only holds the pose). All in seconds as animated in Blockbench. The next hit may only
	 * cancel this one once the motion has ended, so the two poses always meet (no flailing blend between them). A whole
	 * combo can also be one "hit" with several impacts.
	 */
	public record ComboHit(RawAnimation animation, double length, double[] impacts, double motionEnd) {
		public ComboHit(String animation, double length, double impact, double motionEnd) {
			this(RawAnimation.begin().thenPlayAndHold(animation), length, new double[] {impact}, motionEnd);
		}

		public ComboHit(String animation, double length, double[] impacts, double motionEnd) {
			this(RawAnimation.begin().thenPlayAndHold(animation), length, impacts, motionEnd);
		}
	}

	/** The melee combo, hit by hit; empty for a character that just uses the single attack animation. */
	protected List<ComboHit> comboHits() {
		return List.of();
	}

	/** The animation after the last hit when nothing follows, and its length in seconds (as animated). */
	@Nullable
	protected RawAnimation comboEndAnimation() {
		return null;
	}

	/**
	 * A charged attack: a wind-up, a spin that loops (exactly one loop of the animation per spinTicks, so it can end
	 * on a loop and the finish follows without a jump), and a finishing swing that lands at finishImpact.
	 */
	public record ChargedAttack(RawAnimation start, int startTicks, RawAnimation spin, int spinTicks, RawAnimation finish,
			int finishTicks, int finishImpact) {
	}

	/**
	 * Her charged attack (null: none). After her combo's last hit, with an enemy still in reach, every second time
	 * or straight away when she's surrounded (3+ enemies within 4 blocks), in place of starting the combo over:
	 * she winds up, spins (slow, like sneaking, hitting all round her every half second, at least two loops and on
	 * while anything's in reach, six at most), then finishes with one harder hit all round her.
	 */
	@Nullable
	protected ChargedAttack chargedAttack() {
		return null;
	}

	/** Each of the spin's hits, and the finish, as a share of her attack. */
	protected float spinHitDamage() {
		return 0.6F;
	}

	protected float finishHitDamage() {
		return 1.5F;
	}

	/** A spin hit or the finish just landed (effects: particles, sounds). */
	protected void onChargedHit(boolean finish) {
	}

	protected double comboEndLength() {
		return 0;
	}

	/** How far (blocks) she may step forward with each combo hit. */
	protected double comboTravel() {
		return 0.5;
	}

	/** How much faster than animated the combo plays in-game. */
	protected double comboSpeed() {
		return 1.0;
	}

	private int comboTicks(double seconds) {
		return Math.max(1, (int) Math.round(seconds / comboSpeed() * 20));
	}

	/** The skill and burst animations, named in the character's animation file. */
	protected abstract RawAnimation skillAnimation();

	protected abstract RawAnimation burstAnimation();

	// ---- Data -------------------------------------------------------------------------------------------------

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		entityData.define(MODE, CompanionMode.FOLLOW.ordinal());
		entityData.define(LEVEL, 1);
		entityData.define(ACTION, ACTION_NONE);
		entityData.define(COMBAT, false);
		entityData.define(COMBO, 0);
		entityData.define(DASHING, false);
		entityData.define(CHARGE, 0);
		entityData.define(SETTINGS, DEFAULT_SETTINGS);
		entityData.define(BEHAVIOR, CompanionBehavior.AGGRESSIVE.ordinal());
		entityData.define(OFF_FIELD, false);
		entityData.define(ABILITIES, 0);
	}

	// ---- Menu settings ----------------------------------------------------------------------------------------

	public boolean setting(int bit) {
		return (entityData.get(SETTINGS) & bit) != 0;
	}

	public void toggleSetting(int bit) {
		entityData.set(SETTINGS, entityData.get(SETTINGS) ^ bit);
	}

	public CompanionBehavior behavior() {
		return CompanionBehavior.values()[Math.floorMod(entityData.get(BEHAVIOR), CompanionBehavior.values().length)];
	}

	public void setBehavior(CompanionBehavior behavior) {
		entityData.set(BEHAVIOR, behavior.ordinal());
		if (behavior == CompanionBehavior.PASSIVE) {
			setTarget(null);
			resetCombo();
		}
	}

	/** Whether the magic weapon overlay ("weapon_aura" bone) is showing; a character's burst can turn it on. */
	public boolean weaponAuraActive() {
		return false;
	}

	/** Holding a weapon (menu weapon slot): it replaces her own weapon's damage (her level bonus still counts). */
	public boolean holdsSwappedWeapon() {
		return CompanionMenu.isWeapon(getMainHandItem());
	}

	/**
	 * Whether the weapon you gave her is what's drawn. The menu's weapon toggle is just the look: off, she keeps the
	 * given weapon's damage but shows her own claymore.
	 */
	public boolean showsGivenWeapon() {
		return holdsSwappedWeapon() && setting(SHOW_WEAPON);
	}

	/** Her jump: 1.5 blocks high (vanilla's is 1.25). */
	private static final float JUMP_POWER = 0.465F;

	@Override
	protected float getJumpPower() {
		return JUMP_POWER * getBlockJumpFactor();
	}

	private void updateWeaponSwap() {
		var attack = getAttribute(Attributes.ATTACK_DAMAGE);
		if (attack == null) {
			return;
		}
		boolean swapped = holdsSwappedWeapon();
		if (swapped != (attack.getModifier(WEAPON_SWAP) != null)) {
			attack.removeModifier(WEAPON_SWAP);
			if (swapped) {
				// The held weapon adds its own damage (as for a player: 1 + the weapon); her claymore's base is set aside.
				attack.addTransientModifier(new AttributeModifier(WEAPON_SWAP, "Swapped weapon", 1.0 - attack.getBaseValue(),
						AttributeModifier.Operation.ADDITION));
			}
		}
	}

	public CompanionMode mode() {
		return CompanionMode.values()[Math.floorMod(entityData.get(MODE), CompanionMode.values().length)];
	}

	public void setMode(CompanionMode mode) {
		entityData.set(MODE, mode.ordinal());
		setOrderedToSit(mode == CompanionMode.STAY);
		if (mode == CompanionMode.WANDER) {
			restrictTo(blockPosition(), 16);
		} else {
			clearRestriction();
		}
		if (mode != CompanionMode.FOLLOW) {
			getNavigation().stop();
		}
	}

	public int companionLevel() {
		return entityData.get(LEVEL);
	}

	public int xp() {
		return xp;
	}

	public boolean inCombat() {
		return entityData.get(COMBAT);
	}

	private boolean calmPose = true;   // client side: idle or walking (the back weapon may show); not run/jump/swim/sit
	private int ticksSinceFight = 1000; // client side
	/**
	 * Client side, her weapons appearing and vanishing (like Genshin's): how far each has materialised (0..1,
	 * eased by the model per frame), whether it's wanted now, and the model's frame clock for that.
	 */
	public float handWeaponIn, backWeaponIn;
	public boolean handWeaponWanted, backWeaponWanted;
	/** Client side: ticks of sparkle left over the claymore just summoned to her hand (it appears at once). */
	public float handWeaponSpark;
	/** Client side: how far each prop (by name) has appeared, 0-1, whether its animation wants it, and the clock. */
	public final java.util.Map<String, Float> propIn = new java.util.HashMap<>();
	public final java.util.Map<String, Boolean> propWanted = new java.util.HashMap<>();
	public float propLastTime;
	/**
	 * Client side: for a prop whose animation sets its size (the teapot popping in mid-animation), that size now
	 * (largest axis) and whether it's growing (1), shrinking (-1) or steady (0), for the sparkle.
	 */
	public final java.util.Map<String, Float> propScale = new java.util.HashMap<>();
	public final java.util.Map<String, Integer> propPop = new java.util.HashMap<>();
	public float weaponLastTime = -1.0F;
	/** Server side, while someone plays as her: whether she's moving faster than a walk (jogging is the default). */
	public boolean pilotRunning;
	/** Server side, while someone plays as her: whether they're dashing (their client times it; i-frames). */
	public boolean pilotDashing;
	/** Server side, while someone plays as her: Left Shift held past the dash, so she keeps sprinting. */
	public boolean pilotSprintHeld;
	/** Server side, played as: a click that came while a hit was playing, kept this many ticks for when it can follow. */
	private int queuedAttack;
	/** Server side, played as: left click still held for the charged attack (let go: she finishes). */
	private boolean chargeHeld;
	/** Server side, played as, for a character with no combo (plain punches): ticks until the next punch. */
	private int punchCooldown;
	/** Played as, X: what her hits land on, one of the TARGETS_ modes (see {@link #isFoe}). */
	public int pilotTargets;
	/**
	 * Client side, on the client playing as her: Left Shift held (sprinting), walking toggled on (Left Ctrl), jump
	 * held, and ticks of dash left (set each tick by its keys).
	 */
	public boolean pilotSprinting, pilotWalking, pilotJump;
	public int pilotDashTicks;
	/** When she was befriended (game time): her place in her owner's party. 0 for companions from before this. */
	private long befriendedAt;
	private float lookBlend = 1.0F;  // client side: how much the head looks at things (0 in a fight), eased per tick
	private float lookBlendO = 1.0F;

	/** Client side: 0..1, how much the head should follow the look direction this frame (eases in and out). */
	public float lookBlend(float partialTick) {
		return Mth.lerp(partialTick, lookBlendO, lookBlend);
	}

	/**
	 * Client side, the skirt's motion this frame (radians), worked out by the model and drawn by the renderer:
	 * how far the front swings with the forward leg and the back with the leg kicking back, plus three springs
	 * (angle, speed) that give it weight: the lean (the hem trails behind her when she moves, swings past and
	 * settles when she stops), the flare (out when she spins or falls, bouncing on landing) and the twist (the hem
	 * lagging behind a turn or spin). The rest is the tracker's memory between frames.
	 */
	public float skirtFront, skirtBack;
	/**
	 * Client side, for her eyes: smiling until this tick; how long she has been sitting; how long her owner has
	 * been far away (coming back after 10 seconds or more earns a smile).
	 */
	public int eyesHappyUntil, sittingTicks, ownerAwayTicks;
	/**
	 * Client side, the moving eyes (experiments, see client EyeRig): where the pupils point (-1..1 each way), the
	 * idle glance and when it changes, how open the eyes are, pupil size, who she's meeting the eyes of, the frame
	 * clock, and her head's place in the world from the last frame (from the renderer).
	 */
	public float eyeLookX, eyeLookY, eyeGlanceX, eyeGlanceY, eyeOpen = 1.0F, pupilSize = 1.0F, eyeLastTime = -1.0F;
	public int eyeGlanceUntil, eyeContactId, eyeContactUntil;
	@Nullable
	public org.joml.Matrix4f eyeHeadMatrix;
	/** Client side, the menu portrait: eyes shut for this pose (her armor pose). */
	public boolean menuEyesClosed;
	/** Entity event: she's happy (levelled up, her menu opened). Hearts (event 7) make her smile too. */
	public static final byte EVENT_HAPPY = 100;
	public final float[] skirtLean = new float[2], skirtFlare = new float[2], skirtTwist = new float[2];
	public float skirtLastTime = -1.0F, skirtLastSpin, skirtLastYaw;
	public double skirtLastX, skirtLastY, skirtLastZ;

	// ---- Menu portrait (a client-only copy shown in her menu) --------------------------------------------------

	@Nullable
	private RawAnimation menuAnimation;
	private int menuBlendTicks = 5;
	private double menuSpeed = 1.0;
	private boolean menuShowsWeapon;

	/** Client side, menu portrait copies only: the pose to play (null = normal behaviour) and whether her weapon is out. */
	public void setMenuPose(@Nullable RawAnimation animation, boolean showWeapon) {
		setMenuPose(animation, showWeapon, false);
	}

	/** The menu portrait's pose, whether her weapon is out, and whether her eyes are shut for it. */
	public void setMenuPose(@Nullable RawAnimation animation, boolean showWeapon, boolean eyesClosed) {
		setMenuPose(animation, showWeapon, eyesClosed, 5);
	}

	/** ... and how many ticks she takes to blend into it. */
	public void setMenuPose(@Nullable RawAnimation animation, boolean showWeapon, boolean eyesClosed, int blendTicks) {
		setMenuPose(animation, showWeapon, eyesClosed, blendTicks, 1.0);
	}

	/** ... and how fast it plays (the blend in counts at that speed too). */
	public void setMenuPose(@Nullable RawAnimation animation, boolean showWeapon, boolean eyesClosed, int blendTicks, double speed) {
		menuBlendTicks = blendTicks;
		menuSpeed = speed;
		menuEyesClosed = eyesClosed;
		menuAnimation = animation;
		menuShowsWeapon = showWeapon;
	}

	/** Copies what the portrait needs from the real companion: her gear and her visibility settings. */
	public void copyLookFrom(CompanionEntity real) {
		for (EquipmentSlot slot : CompanionMenu.SLOTS) {
			ItemStack want = real.getItemBySlot(slot);
			if (!ItemStack.matches(getItemBySlot(slot), want)) {
				setItemSlot(slot, want.copy());
			}
		}
		entityData.set(SETTINGS, real.entityData.get(SETTINGS));
	}

	/** Degrees the menu portrait turns for the weapon close-up (each character holds their weapon differently). */
	public float menuWeaponTurn() {
		return 0.0F;
	}

	/** Client side: the weapon is in her hand (swinging, casting, or posing in the menu). */
	public boolean showsWeaponInHand() {
		// The burst's glowing blade (Noelle's Sweeping Time) keeps her weapon out for as long as it lasts.
		return playingMove || menuShowsWeapon || weaponAuraActive();
	}

	/** Seconds the claymore keeps hovering behind her after a fight before she dismisses it. */
	protected int backWeaponLingerTicks() {
		return 100;
	}

	/**
	 * Client side: whether the weapon hovers behind her (the "weapon_back" bone): just after attacking or a fight,
	 * while she's standing or walking. Running, jumping, swimming or sitting dismiss it, and so does time.
	 */
	public boolean showBackWeapon() {
		return !playingMove && calmPose && (inCombat() || ticksSinceFight < backWeaponLingerTicks());
	}

	/** Client side: true while an attack, skill or burst animation is playing (the head follows the animation then). */
	public boolean playingMove() {
		return playingMove;
	}

	public int action() {
		return entityData.get(ACTION);
	}

	/** Shows the skill or burst animation for this many ticks on every client. */
	protected void playAction(int action, int ticks) {
		entityData.set(ACTION, action);
		actionTicks = ticks;
		resetCombo();
	}

	// ---- Combo ------------------------------------------------------------------------------------------------

	/** Server side: whether she's free to start the next combo hit (none playing, no skill, burst or charged attack). */
	public boolean canStartComboHit() {
		if (comboHits().isEmpty() || action() != ACTION_NONE || chargeStage != 0) {
			return false;
		}
		// Animation cancel: once the hit has landed (plus a beat), the next one can cut its recovery short.
		return comboPieceTicks == 0 || comboImpactIndex >= comboImpacts.length && comboCancelTicks <= 0;
	}

	/** Her melee reach in blocks from her middle to the target's edge: the length of the weapon swing. */
	public double meleeReach() {
		return 2.0;
	}

	/** Seconds after a hit's impact before the next hit may cancel its recovery. */
	protected double comboCancelDelay() {
		return 0.15;
	}

	/** Server side: start the next hit of the combo on this target; its damage lands at the hit's impact time. */
	public void startComboHit(LivingEntity target) {
		List<ComboHit> hits = comboHits();
		int index = comboNext;
		comboNext = (index + 1) % hits.size();
		startPiece(hits.get(index), index, index + 1, target);
	}

	/** The charged attack on this target (after the combo's last hit; the combo starts over after it). */
	private void startCharged(@Nullable LivingEntity target) {
		resetCombo();
		chargeStage = CHARGE_START;
		chargeTicks = 0;
		spinLoops = 0;
		comboTarget = target;
		combatTicks = 60;
		if (target != null) {
			setYRot((float) (Mth.atan2(target.getZ() - getZ(), target.getX() - getX()) * Mth.RAD_TO_DEG) - 90.0F);
			setYBodyRot(getYRot());
		}
		entityData.set(CHARGE, chargeStage);
	}

	/** Server side, each tick: the charged attack's stages (see {@link #chargedAttack()}). */
	private void tickCharged() {
		ChargedAttack charged = chargedAttack();
		if (chargeStage == 0 || charged == null) {
			chargeStage = 0;
			entityData.set(CHARGE, 0);
			return;
		}
		chargeTicks++;
		if (chargeStage != CHARGE_SPIN) {
			// The wind-up and the finish are planted.
			getNavigation().stop();
			getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0);
		}
		switch (chargeStage) {
			case CHARGE_START -> {
				if (chargeTicks >= charged.startTicks()) {
					// Played as and already let go (held under a second): straight from the wind-up into the finish.
					chargeStage = isPlayed() && !chargeHeld ? CHARGE_FINISH : CHARGE_SPIN;
					chargeTicks = 0;
				}
			}
			case CHARGE_SPIN -> {
				if (chargeTicks % 10 == 0) {
					chargedHit(spinHitDamage(), 1.0, 0.3F);
					onChargedHit(false);
				}
				if (chargeTicks % charged.spinTicks() == 0) {
					spinLoops++;
					// Played as: spins while you hold, finishing at the end of a loop (6 at most, until stamina).
					if (isPlayed() ? !chargeHeld || spinLoops >= 6 : spinLoops >= 6 || spinLoops >= 2 && comboFoe(0.0) == null) {
						chargeStage = CHARGE_FINISH;
						chargeTicks = 0;
					}
				}
			}
			default -> {
				// The finish starts after its short blend in, so its hit and its end come that much later.
				if (chargeTicks == charged.finishImpact() + FINISH_BLEND) {
					chargedHit(finishHitDamage(), 1.25, 0.8F);
					onChargedHit(true);
				}
				if (chargeTicks >= charged.finishTicks() + FINISH_BLEND) {
					chargeStage = 0;
				}
			}
		}
		entityData.set(CHARGE, chargeStage);
	}

	/** Every enemy all round her within her reach (times reachScale), at this share of her attack. */
	private void chargedHit(float share, double reachScale, float knockback) {
		float damage = (float) getAttributeValue(Attributes.ATTACK_DAMAGE) * share;
		double reach = meleeReach() * meleeReachScale() * reachScale;
		for (LivingEntity near : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(reach + 1.0, 1.0, reach + 1.0),
				e -> isFoe(e) && distanceTo(e) <= reach + e.getBbWidth() / 2.0)) {
			if (near.hurt(damageSources().mobAttack(this), damage)) {
				near.knockback(knockback, getX() - near.getX(), getZ() - near.getZ());
			}
		}
	}

	/** Surrounded: three or more enemies within 4 blocks. */
	private boolean surrounded() {
		return level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(4.0), mob -> mob instanceof Enemy && mob.isAlive()
				&& !(mob instanceof TamableAnimal tame && tame.isTame())).size() >= 3;
	}

	private void startPiece(ComboHit hit, int index, int phase, @Nullable LivingEntity target) {
		comboHitIndex = index;
		comboCount = (comboCount + 1) & 0xFFFFF;
		comboPieceTicks = comboTicks(hit.length());
		comboImpacts = new int[hit.impacts().length];
		for (int i = 0; i < comboImpacts.length; i++) {
			comboImpacts[i] = Math.min(comboPieceTicks, comboTicks(hit.impacts()[i]));
		}
		java.util.Arrays.sort(comboImpacts);
		comboImpactIndex = 0;
		comboElapsed = 0;
		int lastImpact = comboImpacts.length == 0 ? 0 : comboImpacts[comboImpacts.length - 1];
		comboCancelTicks = Math.min(comboPieceTicks,
				Math.max(lastImpact + comboTicks(comboCancelDelay()), (int) Math.ceil(hit.motionEnd() / comboSpeed() * 20) + 1));
		comboTravelled = 0; // each hit gets its own small step forward
		comboWindowTicks = 0;
		comboEndTicks = 0;
		comboTarget = target;
		combatTicks = 60;
		// Aim at the target, but turn there quickly over the first ticks instead of snapping (a snap reads as a spin).
		// Played as (no target): the way you turned her.
		comboAimYaw = target == null ? getYRot() : (float) (Mth.atan2(target.getZ() - getZ(), target.getX() - getX()) * Mth.RAD_TO_DEG) - 90.0F;
		comboYaw = yBodyRot;
		lockFacing();
		entityData.set(COMBO, comboCount << 3 | phase);
	}

	private void lockFacing() {
		setYRot(comboYaw);
		yRotO = comboYaw;
		setYBodyRot(comboYaw);
		yBodyRotO = comboYaw;
		setYHeadRot(comboYaw);
		yHeadRotO = comboYaw;
	}

	@Override
	public void tick() {
		super.tick();
		if (!level().isClientSide) {
			updateWeaponSwap();
		}
		if (level().isClientSide) {
			lookBlendO = lookBlend;
			float want = playingMove || inCombat() ? 0.0F : 1.0F;
			lookBlend += Mth.clamp(want - lookBlend, -0.1F, 0.1F); // half a second to ease the look in or out
			ticksSinceFight = playingMove || inCombat() ? 0 : ticksSinceFight + 1;
			sittingTicks = isInSittingPose() ? sittingTicks + 1 : 0;
			LivingEntity owner = getOwner();
			if (owner != null) {
				double away = distanceToSqr(owner);
				if (away > 12 * 12) {
					ownerAwayTicks++;
				} else if (away < 4 * 4) {
					if (ownerAwayTicks >= 200) {
						eyesHappyUntil = tickCount + 60;   // you're back
					}
					ownerAwayTicks = 0;
				}
			}
		}
		// Locked facing for the whole of a hit: the AI's look/turn this tick is undone before it's sent to players.
		// Played as, your client owns her facing (it aims each hit itself).
		if (!level().isClientSide && comboPieceTicks > 0 && !isPlayed()) {
			comboYaw = Mth.approachDegrees(comboYaw, comboAimYaw, 20.0F);
			lockFacing();
		}
	}

	private void resetCombo() {
		comboNext = 0;
		comboPieceTicks = 0;
		comboWindowTicks = 0;
		comboEndTicks = 0;
		comboImpacts = new int[0];
		comboImpactIndex = 0;
		comboTarget = null;
		entityData.set(COMBO, comboCount << 3);
	}

	private void tickCombo() {
		if (comboCancelTicks > 0) {
			comboCancelTicks--;
		}
		if (comboPieceTicks > 0) {
			comboElapsed++;
			while (comboImpactIndex < comboImpacts.length && comboElapsed >= comboImpacts[comboImpactIndex]) {
				comboImpactIndex++;
				if (isPlayed()) {
					playedImpact();
					continue;
				}
				LivingEntity target = comboFoe(0.5); // a little give
				if (target != null) {
					var attack = getAttribute(Attributes.ATTACK_DAMAGE);
					float bonus = comboBonusDamage(comboHitIndex);
					if (attack != null && bonus != 0) {
						attack.addTransientModifier(new AttributeModifier(COMBO_BONUS, "Combo hit", bonus, AttributeModifier.Operation.ADDITION));
					}
					try {
						doHurtTarget(target);
						cleave(target);
					} finally {
						if (attack != null) {
							attack.removeModifier(COMBO_BONUS);
						}
					}
				}
			}
		}
		if (comboPieceTicks > 0 && --comboPieceTicks == 0 && isPlayed()) {
			// Played as: nothing follows by itself. After the last hit, her ending; else she holds the pose for your
			// next click (the same window as always).
			boolean lastHit = comboNext == 0 && comboHitIndex == comboHits().size() - 1;
			if (lastHit) {
				combosFinished++;
			}
			if (lastHit && comboEndAnimation() != null) {
				comboEndTicks = comboTicks(comboEndLength());
				comboCount = (comboCount + 1) & 0xFFFFF;
				entityData.set(COMBO, comboCount << 3 | COMBO_END_PHASE);
			} else {
				comboWindowTicks = PLAY_COMBO_WINDOW_TICKS;
			}
		} else if (comboPieceTicks > 0 && --comboPieceTicks == 0) {
			LivingEntity next = comboFoe(0.0);
			boolean lastHit = comboNext == 0 && comboHitIndex == comboHits().size() - 1;
			if (lastHit) {
				combosFinished++;
			}
			if (next != null && action() == ACTION_NONE && lastHit && chargedAttack() != null
					&& (combosFinished % 2 == 0 || surrounded())) {
				// Every second time, or surrounded, still facing someone after the last hit: the charged attack.
				setTarget(next);
				startCharged(next);
			} else if (next != null && action() == ACTION_NONE) {
				// Something's still in reach: straight into the next hit (after the finisher, back to hit 1), no idling.
				setTarget(next);
				startComboHit(next);
			} else if (comboNext == 0 && comboEndAnimation() != null) {
				// The last hit and nothing left in reach: the ending, unless something steps in and starts a new combo.
				comboEndTicks = comboTicks(comboEndLength());
				comboCount = (comboCount + 1) & 0xFFFFF;
				entityData.set(COMBO, comboCount << 3 | COMBO_END_PHASE);
			} else {
				comboWindowTicks = COMBO_WINDOW_TICKS;
			}
		}
		// Played as: a click kept from mid-hit goes as soon as the next hit can.
		if (isPlayed() && queuedAttack > 0) {
			if (canStartComboHit()) {
				queuedAttack = 0;
				startPlayedHit();
				return;
			}
			queuedAttack--;
		}
		// Holding a pose or in the ending: anything hostile stepping into reach picks the combo back up.
		if (!isPlayed() && (comboWindowTicks > 0 || comboEndTicks > 0) && canStartComboHit()) {
			LivingEntity next = comboFoe(0.0);
			if (next != null) {
				setTarget(next);
				startComboHit(next);
				return;
			}
		}
		if (comboWindowTicks > 0 && --comboWindowTicks == 0) {
			resetCombo();
		}
		if (comboEndTicks > 0 && --comboEndTicks == 0) {
			resetCombo();
		}
		if (!isPlayed() && comboPieceTicks > 0 && comboCancelTicks <= 0 && comboImpactIndex >= comboImpacts.length && canStartComboHit()) {
			// Animation cancel onto whoever is in reach (her target first), not only the one she started on.
			LivingEntity next = comboFoe(0.0);
			if (next != null && next != comboTarget) {
				setTarget(next);
				startComboHit(next);
			}
		}
	}

	/** The width (degrees) of a combo swing's arc in front of her; 0 = the swing only hits its one target. */
	protected double cleaveArc() {
		return 0;
	}

	/** The arc of one hit of the combo (0-based); by default every hit has {@link #cleaveArc()}. */
	protected double cleaveArc(int hit) {
		return cleaveArc();
	}

	/** Extra damage for one hit of the combo (0-based), on top of her attack. */
	protected float comboBonusDamage(int hit) {
		return 0;
	}

	/** Which hit of the combo is playing (0-based), for effects that differ per hit. */
	protected int comboHitIndex() {
		return comboHitIndex;
	}

	/** A swing also catches every other hostile in reach inside its arc (full damage, a little knockback). */
	private void cleave(LivingEntity primary) {
		double arc = cleaveArc(comboHitIndex);
		if (arc <= 0) {
			return;
		}
		float damage = (float) getAttributeValue(Attributes.ATTACK_DAMAGE);
		double reach = meleeReach() * meleeReachScale() + 1.0;
		for (Mob mob : level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(reach), mob -> mob != primary && isFoe(mob)
				&& inMeleeReach(mob, 0.5))) {
			double angle = Mth.atan2(mob.getZ() - getZ(), mob.getX() - getX()) * Mth.RAD_TO_DEG - 90.0;
			float yaw = attackYaw();
			if (Math.abs(Mth.wrapDegrees(angle - yaw)) <= arc / 2 && mob.hurt(damageSources().mobAttack(this), damage)) {
				mob.knockback(0.3, Mth.sin(yaw * Mth.DEG_TO_RAD), -Mth.cos(yaw * Mth.DEG_TO_RAD));
			}
		}
	}

	private boolean inMeleeReach(LivingEntity target, double give) {
		return distanceTo(target) <= meleeReach() * meleeReachScale() + target.getBbWidth() / 2.0 + give;
	}

	/**
	 * Who the next hit is for: the current combo target or her target if still in reach, else (once she's someone's
	 * companion) the nearest hostile in reach, so a combo flows from one mob to the next.
	 */
	@Nullable
	private LivingEntity comboFoe(double give) {
		for (LivingEntity candidate : new LivingEntity[] {comboTarget, getTarget()}) {
			if (candidate != null && candidate.isAlive() && inMeleeReach(candidate, give) && canAttack(candidate)) {
				return candidate;
			}
		}
		if (!isTame() || behavior() != CompanionBehavior.AGGRESSIVE) {
			return null;
		}
		double reach = meleeReach() * meleeReachScale() + 1.0 + give;
		return level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(reach), mob -> mob instanceof Enemy && mob.isAlive()
						&& !(mob instanceof TamableAnimal tame && tame.isTame()) && inMeleeReach(mob, give) && canAttack(mob))
				.stream().min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
	}

	/** Game tests: one tick of what her AI does for her skill and burst (timed effects), for a companion with no AI. */
	public void tickAbilitiesForTests() {
		tickAbilities();
	}

	/** How long the skill and burst animations last, in ticks (their animation_length times 20). */
	protected int skillAnimationTicks() {
		return 18;
	}

	protected int burstAnimationTicks() {
		return 28;
	}

	public int energy() {
		return energy;
	}

	public int burstEnergyCost() {
		return burstCost();
	}

	public int skillCooldown() {
		return skillCooldown;
	}

	public static int xpToNext(int level) {
		return 10 + level * 5;
	}

	public void setCompanionLevel(int level) {
		entityData.set(LEVEL, Math.max(1, Math.min(MAX_LEVEL, level)));
		applyLevelStats();
	}

	/** XP from kills; levels add 2 health and 0.5 attack each. */
	public void addXp(int amount) {
		if (companionLevel() >= MAX_LEVEL) {
			return;
		}
		xp += amount;
		boolean leveled = false;
		while (companionLevel() < MAX_LEVEL && xp >= xpToNext(companionLevel())) {
			xp -= xpToNext(companionLevel());
			setCompanionLevel(companionLevel() + 1);
			leveled = true;
		}
		if (leveled) {
			setHealth(getMaxHealth());
			if (level() instanceof ServerLevel server) {
				server.sendParticles(element().dust(), getX(), getY() + 1, getZ(), 30, 0.5, 0.8, 0.5, 0);
				playSound(SoundEvents.PLAYER_LEVELUP, 0.6F, 1.4F);
				level().broadcastEntityEvent(this, EVENT_HAPPY);
			}
			if (getOwner() instanceof Player owner) {
				owner.displayClientMessage(Component.translatable("enshining.companion.level_up", getDisplayName(), companionLevel()), true);
			}
		}
	}

	private void applyLevelStats() {
		int bonusLevels = companionLevel() - 1;
		setModifier(Attributes.MAX_HEALTH, LEVEL_HEALTH, "Companion level health", bonusLevels * 2.0);
		setModifier(Attributes.ATTACK_DAMAGE, LEVEL_ATTACK, "Companion level attack", bonusLevels * 0.25);
	}

	protected void setModifier(net.minecraft.world.entity.ai.attributes.Attribute attribute, UUID id, String name, double amount) {
		AttributeInstance instance = getAttribute(attribute);
		if (instance == null) {
			return;
		}
		instance.removeModifier(id);
		if (amount != 0) {
			instance.addPermanentModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.ADDITION));
		}
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putString("Mode", mode().name());
		tag.putInt("CompanionLevel", companionLevel());
		tag.putInt("CompanionXp", xp);
		tag.putInt("SkillCooldown", skillCooldown);
		tag.putInt("Energy", energy);
		tag.putInt("MenuSettings", entityData.get(SETTINGS));
		tag.putString("Behavior", behavior().name());
		tag.putLong("BefriendedAt", befriendedAt);
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		try {
			entityData.set(MODE, CompanionMode.valueOf(tag.getString("Mode")).ordinal());
		} catch (IllegalArgumentException ignored) {
			entityData.set(MODE, CompanionMode.FOLLOW.ordinal());
		}
		setCompanionLevel(Math.max(1, tag.getInt("CompanionLevel")));
		xp = tag.getInt("CompanionXp");
		skillCooldown = tag.getInt("SkillCooldown");
		energy = tag.getInt("Energy");
		if (tag.contains("MenuSettings")) {
			entityData.set(SETTINGS, tag.getInt("MenuSettings"));
		}
		befriendedAt = tag.getLong("BefriendedAt");
		try {
			entityData.set(BEHAVIOR, CompanionBehavior.valueOf(tag.getString("Behavior")).ordinal());
		} catch (IllegalArgumentException ignored) {
			entityData.set(BEHAVIOR, CompanionBehavior.AGGRESSIVE.ordinal());
		}
	}

	// ---- Being played as (see play.PlayAs) -------------------------------------------------------------------------

	/**
	 * Speeds while played as (before ground friction; on normal ground she covers {@link #GROUND} times this a tick):
	 * the jog (her default, faster than a player's sprint), the walk (Left Ctrl toggles it, just under a player's
	 * walk), the dash (a Left Shift press, a burst) and the sprint (Left Shift still held after the dash).
	 */
	private static final float PLAY_WALK = 0.09F, PLAY_JOG = 0.16F, PLAY_DASH = 0.24F, PLAY_SPRINT = 0.21F;
	/** Played as, X: monsters only; everything but friendlies (villagers, golems, pets); everything. */
	public static final int TARGETS_HOSTILE = 0, TARGETS_NOT_FRIENDLY = 1, TARGETS_EVERYTHING = 2, TARGETS_MODES = 3;
	/** Ground covered per tick for each unit of speed, at full input, on normal blocks (1 / (1 - 0.6 * 0.91)). */
	private static final float GROUND = 2.2026F;
	/**
	 * The ground her jog and run animations were made for: her own jog and dash when she runs by herself (blocks a
	 * tick). Driven faster, they play faster to match, so her feet and arms keep pace with the ground.
	 */
	private static final float OWN_JOG_PACE = 0.167F, OWN_DASH_PACE = 0.303F;
	/**
	 * Played as, each hit's step: a quick lunge at the start of the swing, about 1 block (5 ticks of push from
	 * standing, plus the glide after, covers about 11 times this speed on normal ground).
	 */
	private static final int PLAY_STEP_TICKS = 5;
	private static final float PLAY_STEP_SPEED = 0.091F;
	/** How fast she turns to face where your keys send her (degrees per tick). */
	private static final float PLAY_TURN = 45.0F;

	public long befriendedAt() {
		return befriendedAt;
	}

	public boolean isOffField() {
		return entityData.get(OFF_FIELD);
	}

	public void setOffField(boolean off) {
		entityData.set(OFF_FIELD, off);
		noPhysics = off;
		if (off) {
			getNavigation().stop();
			setTarget(null);
			setDeltaMovement(Vec3.ZERO);
		}
		refreshControl();
	}

	/** Her AI stands down while you play as her or she's off-field, and comes back after. */
	public void refreshControl() {
		updateControlFlags();
	}

	@Override
	protected void updateControlFlags() {
		super.updateControlFlags();
		boolean free = !(getControllingPassenger() instanceof Player) && !isOffField();
		if (!free) {
			goalSelector.setControlFlag(Goal.Flag.MOVE, false);
			goalSelector.setControlFlag(Goal.Flag.JUMP, false);
			goalSelector.setControlFlag(Goal.Flag.LOOK, false);
		}
		targetSelector.setControlFlag(Goal.Flag.TARGET, free);
	}

	/** You, playing as her, steer her. */
	@Nullable
	@Override
	public LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player player ? player : super.getControllingPassenger();
	}

	/** Someone is playing as her right now. */
	public boolean isPlayed() {
		return getControllingPassenger() instanceof Player;
	}

	/** The way her swings and sweeps go: where she faces (played as: the way your client turned her). */
	protected float attackYaw() {
		return isPlayed() ? getYRot() : yBodyRot;
	}

	/** Client side, for the E/Q display: her skill cooldown in ticks. */
	public int skillCooldownShown() {
		return entityData.get(ABILITIES) >>> 8;
	}

	/** Client side, for the E/Q display: her energy. */
	public int energyShown() {
		return entityData.get(ABILITIES) & 0xFF;
	}

	/**
	 * Played as, left click: her next hit. A click mid-hit is kept until that hit has landed and swung through, then
	 * the next one follows (Genshin's: mashing never cuts her own hits short).
	 */
	public void playerAttack() {
		if (!isPlayed() || chargeStage != 0 || actionTicks > 0) {
			return;
		}
		if (comboHits().isEmpty()) {
			// No combo (the test dummy): a plain punch at whatever's in front.
			if (punchCooldown <= 0) {
				punchCooldown = 10;
				swing(InteractionHand.MAIN_HAND);
				LivingEntity foe = foeInFront(90.0);
				if (foe != null) {
					doHurtTarget(foe);
				}
			}
			return;
		}
		if (canStartComboHit()) {
			queuedAttack = 0;
			startPlayedHit();
		} else {
			queuedAttack = Math.max(8, (comboPieceTicks > 0 ? comboCancelTicks : 0) + 4);
		}
	}

	private void startPlayedHit() {
		List<ComboHit> hits = comboHits();
		int index = comboNext;
		comboNext = (index + 1) % hits.size();
		startPiece(hits.get(index), index, index + 1, null);
	}

	/**
	 * A played hit lands: on the nearest enemy in her swing's arc (her combo bonus counts), and everything else in the
	 * arc with it. A hit with no arc of its own still catches what's straight ahead (60 degrees).
	 */
	private void playedImpact() {
		double arc = cleaveArc(comboHitIndex);
		LivingEntity primary = foeInFront(arc > 0 ? arc : 60.0);
		if (primary == null) {
			return;
		}
		var attack = getAttribute(Attributes.ATTACK_DAMAGE);
		float bonus = comboBonusDamage(comboHitIndex);
		if (attack != null && bonus != 0) {
			attack.addTransientModifier(new AttributeModifier(COMBO_BONUS, "Combo hit", bonus, AttributeModifier.Operation.ADDITION));
		}
		try {
			doHurtTarget(primary);
			cleave(primary);
		} finally {
			if (attack != null) {
				attack.removeModifier(COMBO_BONUS);
			}
		}
	}

	/** The nearest enemy in her reach inside this arc (degrees) in front of her. */
	@Nullable
	private LivingEntity foeInFront(double arc) {
		float yaw = attackYaw();
		double reach = meleeReach() * meleeReachScale() + 1.0;
		return level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(reach), mob -> isFoe(mob) && inMeleeReach(mob, 0.5)
						&& Math.abs(Mth.wrapDegrees(Mth.atan2(mob.getZ() - getZ(), mob.getX() - getX()) * Mth.RAD_TO_DEG - 90.0 - yaw)) <= arc / 2)
				.stream().min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
	}

	/** Played as, left click held past the tap: the charged attack (it cuts any hit short; the combo restarts). */
	public void playerChargeBegin() {
		if (!isPlayed() || chargeStage != 0 || actionTicks > 0 || chargedAttack() == null) {
			return;
		}
		queuedAttack = 0;
		chargeHeld = true;
		startCharged(null);
	}

	/** Played as, left click let go: she finishes (straight after the wind-up, or at the end of the spin she's in). */
	public void playerChargeEnd() {
		chargeHeld = false;
	}

	/** Played as, E: her skill, if it's off cooldown (it cuts a hit short; the combo restarts). */
	public void playerSkill() {
		if (!isPlayed() || actionTicks > 0 || chargeStage != 0 || skillCooldown > 0) {
			return;
		}
		queuedAttack = 0;
		skillCooldown = skillCooldownTicks();
		energy = Math.min(MAX_ENERGY, energy + 6);
		playAction(ACTION_SKILL, skillAnimationTicks());
		useSkill(getTarget());
	}

	/** Played as, Q: her burst, if she has the energy. */
	public void playerBurst() {
		if (!isPlayed() || actionTicks > 0 || chargeStage != 0 || energy < burstCost()) {
			return;
		}
		queuedAttack = 0;
		energy = 0;
		playAction(ACTION_BURST, burstAnimationTicks());
		useBurst(getTarget());
	}

	/**
	 * Played as, a dash or a jump (or walking off a held pose): cuts short the hit she's in (its damage too, if it
	 * hadn't landed), her ending, or any part of the charged attack; the combo restarts. Skill and burst can't be cut.
	 */
	public void playerCancel() {
		if (!isPlayed()) {
			return;
		}
		if (comboPieceTicks > 0 || comboWindowTicks > 0 || comboEndTicks > 0) {
			queuedAttack = 0;
			resetCombo();
		}
		if (chargeStage != 0) {
			chargeStage = 0;
			chargeHeld = false;
			entityData.set(CHARGE, 0);
		}
	}

	/**
	 * Whom her hits land on. On her own: monsters (not tamed ones). Played as, X picks: monsters only; everything but
	 * friendlies (villagers and traders, golems, tamed animals and horses); or everything, friendlies too. Never
	 * players, companions or armor stands.
	 */
	public boolean isFoe(Entity e) {
		if (e == this || !(e instanceof Mob mob) || !mob.isAlive() || e instanceof CompanionEntity) {
			return false;
		}
		int targets = isPlayed() ? pilotTargets : TARGETS_HOSTILE;
		if (targets == TARGETS_EVERYTHING) {
			return true;
		}
		if (e instanceof TamableAnimal pet && pet.isTame()) {
			return false;
		}
		if (e instanceof Enemy) {
			return true;
		}
		return targets == TARGETS_NOT_FRIENDLY && !(e instanceof net.minecraft.world.entity.npc.Npc)
				&& !(e instanceof net.minecraft.world.entity.animal.AbstractGolem)
				&& !(e instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse && horse.isTamed());
	}

	/** Game tests: where the charged attack is (0 none, 1 wind-up, 2 spin, 3 finish) and which hit comes next. */
	public int chargeStageForTests() {
		return chargeStage;
	}

	public int comboNextForTests() {
		return comboNext;
	}

	/** Nothing climbs onto her except you playing as her (started with force, so this doesn't stop that). */
	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return false;
	}

	/** You sit inside her with your eyes at hers, so the camera turns about her head. */
	@Override
	public double getPassengersRidingOffset() {
		return getEyeHeight() - 1.62 + 0.35;
	}

	/** Client side, played as: a yaw to turn to over the next few ticks (where a hit, charge or burst is aimed). */
	public float pilotAimYaw;
	public int pilotAimTicks;
	// Client side, played as: the hit playing (seen from the server's COMBO), its step forward and the rest of it.
	private int pilotHitSeen = -1;
	private int pilotHitTicks, pilotStepTicks;
	private float pilotStepSpeed;

	/** The yaw your keys point her: forward/side input turned by the camera's yaw (Genshin's camera-relative move). */
	public static float inputYaw(float cameraYaw, float forward, float side) {
		float yaw = cameraYaw * Mth.DEG_TO_RAD;
		double dx = side * Mth.cos(yaw) - forward * Mth.sin(yaw);
		double dz = forward * Mth.cos(yaw) + side * Mth.sin(yaw);
		return (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
	}

	/** Client side, played as: in a hit (planted, apart from its small step forward). */
	public boolean pilotInHit() {
		return pilotHitTicks > 0;
	}

	/** Client side, played as: a combo is showing but no hit is playing (the pose held for your next click, or her ending). */
	public boolean pilotHolding() {
		return pilotHitTicks == 0 && (entityData.get(COMBO) & 7) != 0;
	}

	/** Client side, played as: in any part of the charged attack. */
	public boolean pilotCharging() {
		return entityData.get(CHARGE) != 0;
	}

	/** Client side, played as: you just cut the hit short (dash, jump, skill, charge): free to move at once. */
	public void pilotCancelLocal() {
		pilotHitTicks = 0;
		pilotStepTicks = 0;
	}

	/** Client side, played as: planted (a hit past its step, her skill, the charged wind-up and finish). */
	private boolean pilotPlanted() {
		int charge = entityData.get(CHARGE);
		return pilotHitTicks > 0 || action() == ACTION_SKILL || charge == CHARGE_START || charge == CHARGE_FINISH;
	}

	/**
	 * On the client playing as her: Genshin's movement. Your keys point relative to the camera; she turns to face that
	 * way and runs where she faces (so S turns her round toward you, rather than walking her backwards). A hit plants
	 * her (after its small step forward), turned where it was aimed.
	 */
	@Override
	protected void tickRidden(Player player, Vec3 travelVector) {
		super.tickRidden(player, travelVector);
		if (!level().isClientSide) {
			return;
		}
		int packed = entityData.get(COMBO);
		int phase = packed & 7;
		int count = packed >>> 3;
		if (count != pilotHitSeen) {
			pilotHitSeen = count;
			if (phase >= 1 && phase <= comboHits().size()) {
				ComboHit hit = comboHits().get(phase - 1);
				pilotHitTicks = comboTicks(hit.length());
				pilotStepTicks = Math.min(PLAY_STEP_TICKS, pilotHitTicks);
				pilotStepSpeed = PLAY_STEP_SPEED;
			} else {
				pilotCancelLocal();
			}
		} else {
			if (pilotHitTicks > 0) {
				pilotHitTicks--;
			}
			if (pilotStepTicks > 0) {
				pilotStepTicks--;
			}
		}
		float forward = player.zza;
		float side = player.xxa;
		if (pilotAimTicks > 0) {
			pilotAimTicks--;
			setYRot(Mth.approachDegrees(getYRot(), pilotAimYaw, 35.0F));
		} else if (!pilotPlanted() && forward * forward + side * side > 1.0E-4F) {
			setYRot(Mth.approachDegrees(getYRot(), inputYaw(player.getYRot(), forward, side), PLAY_TURN));
		}
		setXRot(0.0F);
		setYBodyRot(getYRot());
		setYHeadRot(getYRot());
		setJumping(pilotJump);
	}

	@Override
	protected Vec3 getRiddenInput(Player player, Vec3 travelVector) {
		if (pilotHitTicks > 0) {
			return pilotStepTicks > 0 ? new Vec3(0.0, 0.0, 1.0) : Vec3.ZERO;   // a hit's step goes straight ahead
		}
		float amount = Math.min(1.0F, Mth.sqrt(player.xxa * player.xxa + player.zza * player.zza));
		return new Vec3(0.0, 0.0, amount);
	}

	@Override
	protected float getRiddenSpeed(Player player) {
		if (pilotHitTicks > 0) {
			return pilotStepTicks > 0 ? pilotStepSpeed : 0.0F;
		}
		if (pilotPlanted()) {
			return 0.0F;
		}
		if (entityData.get(CHARGE) == CHARGE_SPIN) {
			return PLAY_WALK * 0.3F;   // sneaking pace while she spins
		}
		float speed = pilotDashTicks > 0 ? PLAY_DASH : pilotSprinting ? PLAY_SPRINT : pilotWalking ? PLAY_WALK : PLAY_JOG;
		if (action() == ACTION_BURST) {
			speed *= 0.5F;
		}
		return speed;
	}

	/** Hopping out: Steve steps out at her side (right, left, then behind), not on top of her head. */
	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		for (float turn : new float[] {90.0F, -90.0F, 180.0F}) {
			Vec3 away = getCollisionHorizontalEscapeVector(getBbWidth(), passenger.getBbWidth(), getYRot() + turn);
			Vec3 spot = position().add(away.x, 0.0, away.z);
			if (level().noCollision(passenger, passenger.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(spot))) {
				return spot;
			}
		}
		return position();
	}

	/** Underwater you stay her (vanilla pops riders out of some mounts underwater). */
	@Override
	public boolean dismountsUnderwater() {
		return false;
	}

	@Override
	public void travel(Vec3 travelVector) {
		if (isOffField()) {
			setDeltaMovement(Vec3.ZERO);
			return;
		}
		super.travel(travelVector);
	}

	@Override
	public boolean isInvisible() {
		return isOffField() || super.isInvisible();
	}

	@Override
	public boolean isSilent() {
		return isOffField() || super.isSilent();
	}

	@Override
	public boolean isPushable() {
		return !isOffField() && !isVehicle() && super.isPushable();
	}

	@Override
	public boolean isPickable() {
		return !isOffField() && super.isPickable();
	}

	@Override
	public boolean isAttackable() {
		return !isOffField() && super.isAttackable();
	}

	@Override
	public void die(DamageSource source) {
		if (!level().isClientSide && getFirstPassenger() instanceof ServerPlayer player) {
			com.freshorangeslices.enshining.play.PlayAs.fell(player, this);
		}
		super.die(source);
	}

	// ---- Behaviour --------------------------------------------------------------------------------------------

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(1, new FloatGoal(this));
		// Not vanilla's SitWhenOrderedToGoal: that one also sits whenever the owner isn't loaded (logged off, another
		// dimension), freezing the companion whatever its mode. Ours sits only on Stay.
		goalSelector.addGoal(2, new Goal() {
			{
				setFlags(EnumSet.of(Goal.Flag.JUMP, Goal.Flag.MOVE));
			}

			@Override
			public boolean canUse() {
				return isTame() && mode() == CompanionMode.STAY && !isInWaterOrBubble() && onGround();
			}

			@Override
			public boolean canContinueToUse() {
				return isTame() && mode() == CompanionMode.STAY;
			}

			@Override
			public void start() {
				getNavigation().stop();
				setInSittingPose(true);
			}

			@Override
			public void stop() {
				setInSittingPose(false);
			}
		});
		goalSelector.addGoal(3, new PersonalSpaceGoal(this));
		goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.15, true) {
			@Override
			protected double getAttackReachSqr(LivingEntity target) {
				double reach = meleeReach() * meleeReachScale() + target.getBbWidth() / 2.0;
				return reach * reach;
			}

			@Override
			protected void checkAndPerformAttack(LivingEntity target, double distanceSqr) {
				if (comboHits().isEmpty()) {
					super.checkAndPerformAttack(target, distanceSqr);
					return;
				}
				// The combo sets its own pace: the next hit starts as soon as the last one has played.
				if (distanceSqr <= getAttackReachSqr(target) && canStartComboHit()) {
					startComboHit(target);
				}
			}
		});
		goalSelector.addGoal(5, new FollowOwnerGoal(this, 1.1, 8.0F, 3.0F, false) {
			@Override
			public boolean canUse() {
				return mode() == CompanionMode.FOLLOW && super.canUse();
			}

			@Override
			public boolean canContinueToUse() {
				return mode() == CompanionMode.FOLLOW && super.canContinueToUse();
			}
		});
		goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
		goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
		goalSelector.addGoal(9, new RandomLookAroundGoal(this));

		// Passive: none of these. Wait for leader: only the first three (what you fight, or what hits you or her).
		targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this) {
			@Override
			public boolean canUse() {
				return behavior() != CompanionBehavior.PASSIVE && super.canUse();
			}
		});
		targetSelector.addGoal(2, new OwnerHurtTargetGoal(this) {
			@Override
			public boolean canUse() {
				return behavior() != CompanionBehavior.PASSIVE && super.canUse();
			}
		});
		targetSelector.addGoal(3, new HurtByTargetGoal(this) {
			@Override
			public boolean canUse() {
				return behavior() != CompanionBehavior.PASSIVE && super.canUse();
			}
		});
		targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
				mob -> isTame() && !isOrderedToSit() && behavior() == CompanionBehavior.AGGRESSIVE && !(mob instanceof Creeper)));
	}

	@Override
	public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
		if (target instanceof Creeper) {
			return false;
		}
		if (target instanceof TamableAnimal pet && pet.isTame() && pet.getOwner() == owner) {
			return false;
		}
		if (target instanceof Player victim && owner instanceof Player player && !player.canHarmPlayer(victim)) {
			return false;
		}
		return target != owner;
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (isOffField()) {
			return false;
		}
		// Dashing while you play as her: i-frames, like Genshin's dodge (not against the void or /kill).
		if (getControllingPassenger() instanceof Player && pilotDashing
				&& !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}
		Entity attacker = source.getEntity();
		if (attacker != null && isTame() && attacker.getUUID().equals(getOwnerUUID())) {
			return false; // the owner's stray swings never land
		}
		return super.hurt(source, amount);
	}

	@Override
	public boolean doHurtTarget(Entity target) {
		combatTicks = 60;
		boolean hit = super.doHurtTarget(target);
		if (hit && target instanceof LivingEntity living) {
			energy = Math.min(MAX_ENERGY, energy + 10);
			onHit(living);
		}
		return hit;
	}

	@Override
	protected void customServerAiStep() {
		super.customServerAiStep();
		if (skillCooldown > 0) {
			skillCooldown--;
		}
		tickAbilities();
		tickCombo();
		tickCharged();
		boolean comboActive = comboPieceTicks > 0 || comboWindowTicks > 0 || comboEndTicks > 0 || chargeStage != 0;
		var spinSpeed = getAttribute(Attributes.MOVEMENT_SPEED);
		boolean spinning = chargeStage == CHARGE_SPIN;
		if (spinSpeed != null && spinning != (spinSpeed.getModifier(SPIN_SLOW) != null)) {
			spinSpeed.removeModifier(SPIN_SLOW);
			if (spinning) {
				spinSpeed.addTransientModifier(new AttributeModifier(SPIN_SLOW, "Charged spin", -0.7, AttributeModifier.Operation.MULTIPLY_TOTAL));
			}
		}
		// Footwork: each hit lets her step up to comboTravel() blocks toward her foe (counted from the hit's start),
		// then she holds her ground until the next hit.
		if (comboActive) {
			if (comboLastPos != null) {
				comboTravelled += Math.sqrt(Math.pow(getX() - comboLastPos.x, 2) + Math.pow(getZ() - comboLastPos.z, 2));
			}
			comboLastPos = position();
		} else {
			comboTravelled = 0;
			comboLastPos = null;
		}
		if (comboActive && comboTravelled >= comboTravel()) {
			getNavigation().stop();
			getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0);
		}
		if (actionTicks > 0 && --actionTicks == 0) {
			entityData.set(ACTION, ACTION_NONE);
		}
		// Her skill roots her for its whole animation; her burst lets her move at half speed.
		if (action() == ACTION_SKILL) {
			getNavigation().stop();
			getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0);
		}
		var speed = getAttribute(Attributes.MOVEMENT_SPEED);
		boolean slowed = action() == ACTION_BURST;
		if (speed != null && slowed != (speed.getModifier(BURST_SLOW) != null)) {
			speed.removeModifier(BURST_SLOW);
			if (slowed) {
				speed.addTransientModifier(new AttributeModifier(BURST_SLOW, "Burst", -0.5, AttributeModifier.Operation.MULTIPLY_TOTAL));
			}
		}
		if (getTarget() != null && getTarget().isAlive() || getLastHurtByMob() != null && tickCount - getLastHurtByMobTimestamp() < 60) {
			combatTicks = 60;
		} else if (combatTicks > 0) {
			combatTicks--;
		}
		entityData.set(COMBAT, combatTicks > 0);
		// Sprinting (synced) while chasing or catching up, so clients pick the run animation; strolling stays a walk.
		// Breaking into a run after 2 s or more of walking or standing starts with a 0.5 s dash at 1.35x speed
		// (the run animation), then she jogs at her normal running speed.
		boolean piloted = getControllingPassenger() instanceof Player;
		boolean running = piloted ? pilotRunning && !comboActive
				: !comboActive && getNavigation().isInProgress() && getMoveControl().getSpeedModifier() >= 1.05;
		if (piloted) {
			// Your Left Shift times the dash (at most one a second, until there's stamina) and the sprint after it: both
			// show her run (only the dash gives i-frames).
			dashTicks = running && (pilotDashing || pilotSprintHeld) ? 1 : 0;
		} else {
			if (running && !isSprinting() && calmTicks >= DASH_AFTER_CALM) {
				dashTicks = DASH_TICKS;
			}
			if (!running) {
				dashTicks = 0;
			} else if (dashTicks > 0) {
				dashTicks--;
			}
		}
		calmTicks = running ? 0 : calmTicks + 1;
		setSprinting(running);
		entityData.set(DASHING, dashTicks > 0);
		if (speed != null && (dashTicks > 0) != (speed.getModifier(DASH_SPEED) != null)) {
			speed.removeModifier(DASH_SPEED);
			if (dashTicks > 0) {
				speed.addTransientModifier(new AttributeModifier(DASH_SPEED, "Dash", 0.35, AttributeModifier.Operation.MULTIPLY_TOTAL));
			}
		}

		entityData.set(ABILITIES, Math.min(skillCooldown, 0xFFFFFF) << 8 | Math.min(energy, 255));
		if (punchCooldown > 0) {
			punchCooldown--;
		}
		if (isPlayed()) {
			return;   // your keys cast her skill and burst
		}
		LivingEntity target = getTarget();
		// One at a time: a skill or burst always plays to its end (nothing cuts in, not even the other one).
		if (target == null || !target.isAlive() || distanceToSqr(target) > 36 || comboPieceTicks > 0 || actionTicks > 0 || chargeStage != 0) {
			return;
		}
		if (setting(BURST_ON) && energy >= burstCost()) {
			energy = 0;
			playAction(ACTION_BURST, burstAnimationTicks());
			useBurst(target);
		} else if (setting(SKILL_ON) && skillCooldown <= 0) {
			skillCooldown = skillCooldownTicks();
			energy = Math.min(MAX_ENERGY, energy + 6);
			playAction(ACTION_SKILL, skillAnimationTicks());
			useSkill(target);
		}
	}

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		boolean holdingGift = stack.is(gift());

		if (level().isClientSide) {
			return (holdingGift && !isTame()) || isOwnedBy(player) ? InteractionResult.CONSUME : InteractionResult.PASS;
		}

		if (!isTame()) {
			if (!holdingGift) {
				return InteractionResult.PASS;
			}
			if (!player.getAbilities().instabuild) {
				stack.shrink(1);
			}
			if (random.nextInt(3) == 0 && !net.minecraftforge.event.ForgeEventFactory.onAnimalTame(this, player)) {
				befriend(player);
			} else {
				level().broadcastEntityEvent(this, (byte) 6); // smoke: not yet
			}
			return InteractionResult.SUCCESS;
		}

		if (!isOwnedBy(player)) {
			return InteractionResult.PASS;
		}

		if (holdingGift && getHealth() < getMaxHealth()) {
			if (!player.getAbilities().instabuild) {
				stack.shrink(1);
			}
			heal(6.0F);
			level().broadcastEntityEvent(this, (byte) 7);
			return InteractionResult.SUCCESS;
		}

		if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer serverPlayer
				&& !(player instanceof net.minecraftforge.common.util.FakePlayer)) {
			openMenu(serverPlayer);
			return InteractionResult.SUCCESS;
		}

		return InteractionResult.PASS;
	}

	@Override
	public void handleEntityEvent(byte id) {
		if (id == 7 || id == EVENT_HAPPY) {
			eyesHappyUntil = tickCount + 60;   // three seconds of a smile
		}
		if (id != EVENT_HAPPY) {
			super.handleEntityEvent(id);
		}
	}

	/** Opens her menu (equipment, settings, status) for her owner. */
	public void openMenu(ServerPlayer player) {
		level().broadcastEntityEvent(this, EVENT_HAPPY);   // she smiles when you talk to her
		NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inventory, p) -> new CompanionMenu(id, inventory, this), getDisplayName()),
				buf -> buf.writeVarInt(getId()));
	}

	/** The goals running now, for tests and debugging. */
	public String goalSelectorRunning() {
		StringBuilder out = new StringBuilder();
		goalSelector.getRunningGoals().forEach(g -> out.append(g.getGoal().getClass().getSimpleName()).append(' '));
		targetSelector.getRunningGoals().forEach(g -> out.append('t').append(':').append(g.getGoal().getClass().getSimpleName()).append(' '));
		return out.toString().trim();
	}

	/** Owner by UUID, so it holds even when the owner isn't loaded in this level (and it's cheaper than a lookup). */
	@Override
	public boolean isOwnedBy(LivingEntity entity) {
		return entity != null && isTame() && entity.getUUID().equals(getOwnerUUID());
	}

	/** Befriends this companion to the player at once (the gift roll already passed). */
	public void befriend(Player player) {
		tame(player);
		befriendedAt = level().getGameTime();
		getNavigation().stop();
		setTarget(null);
		setMode(CompanionMode.FOLLOW);
		setPersistenceRequired();
		level().broadcastEntityEvent(this, (byte) 7); // hearts
		player.displayClientMessage(Component.translatable("enshining.companion.befriended", getDisplayName()), true);
	}

	// ---- Effects helpers --------------------------------------------------------------------------------------

	/** A ring of particles around a point, for skill and burst visuals. */
	protected void ring(ParticleOptions particle, double x, double y, double z, double radius, int count) {
		if (level() instanceof ServerLevel server) {
			for (int i = 0; i < count; i++) {
				double angle = (Math.PI * 2 * i) / count;
				server.sendParticles(particle, x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius, 1, 0, 0.05, 0, 0);
			}
		}
	}

	protected void sound(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
		level().playSound(null, getX(), getY(), getZ(), sound, SoundSource.NEUTRAL, volume, pitch);
	}

	// ---- Animal leftovers -------------------------------------------------------------------------------------

	@Nullable
	@Override
	public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
		return null;
	}

	@Override
	public boolean isFood(ItemStack stack) {
		return false;
	}

	// ---- Animation --------------------------------------------------------------------------------------------

	/**
	 * One controller, decided on each client from synced state (no server-sent triggers): the burst or skill while one
	 * is playing, the attack while swinging (restarted on every new swing), else sit, walk or idle.
	 */
	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "main", 3, state -> {
			if (menuAnimation != null) {
				// A menu portrait copy: plays whatever pose the menu asks for.
				playingMove = false;
				state.getController().setAnimationSpeed(menuSpeed);
				state.getController().setTransitionLength(menuBlendTicks);
				return state.setAndContinue(menuAnimation);
			}
			int action = action();
			playingMove = true;
			int charge = entityData.get(CHARGE);
			ChargedAttack charged = chargedAttack();
			if (charge != 0 && charged != null && action == ACTION_NONE) {
				if (charge != chargeShown) {
					// Into the wind-up over 2 ticks; wind-up to spin at once (it starts where the wind-up ends); into the
					// finish over FINISH_BLEND (its first frame doesn't match), from the spin's pose counted within one turn.
					state.getController().setAnimationSpeed(1.0);
					if (charge == CHARGE_FINISH) {
						unwindSnapshots();
					}
					state.getController().setTransitionLength(chargeShown == 0 ? 2 : charge == CHARGE_FINISH ? FINISH_BLEND : 0);
					if (chargeShown == 0) {
						state.getController().forceAnimationReset();
					}
					chargeShown = charge;
				}
				return state.setAndContinue(charge == CHARGE_START ? charged.start() : charge == CHARGE_SPIN ? charged.spin() : charged.finish());
			}
			chargeShown = 0;
			if (action != ACTION_NONE) {
				if (action != actionShown) {
					// A new skill or burst: from its first frame, at its own speed, with a short blend in.
					actionShown = action;
					state.getController().setAnimationSpeed(1.0);
					state.getController().setTransitionLength(2);
					state.getController().forceAnimationReset();
				}
				return state.setAndContinue(action == ACTION_BURST ? burstAnimation() : skillAnimation());
			}
			actionShown = ACTION_NONE;
			if (!comboHits().isEmpty()) {
				PlayState combo = comboState(state);
				if (combo != null) {
					comboShowing = true;
					return combo;
				}
				if (comboShowing) {
					// Leaving the combo for idle/walk: blend back the short way, not unwinding its spins.
					comboShowing = false;
					unwindSnapshots();
				}
			}
			state.getController().setAnimationSpeed(1.0);
			state.getController().setTransitionLength(6); // walk/run/idle ease into each other
			boolean newSwing = swinging && !wasSwinging;
			wasSwinging = swinging;
			if (newSwing) {
				state.getController().forceAnimationReset();
				return state.setAndContinue(ATTACK);
			}
			if (state.isCurrentAnimation(ATTACK) && !state.getController().hasAnimationFinished()) {
				return PlayState.CONTINUE;
			}
			playingMove = false;
			calmPose = false;
			boolean wasIdle = idling;
			idling = false;
			if (isInSittingPose()) {
				return state.setAndContinue(SIT);
			}
			if (isInWater()) {
				return state.setAndContinue(SWIM);
			}
			if (!onGround() && !isPassenger()) {
				return state.setAndContinue(JUMP);
			}
			if (state.isMoving()) {
				calmPose = !isSprinting();
				if (isSprinting()) {
					boolean dash = entityData.get(DASHING) || !hasAnimation("animation.companion.jog");
					if (getControllingPassenger() instanceof Player) {
						// Driven faster than she runs by herself: the cycle speeds up to match (steady while she
						// keeps running, so GeckoLib's clock doesn't jump: the dash and the sprint after it share the
						// sprint's pace).
						state.getController().setAnimationSpeed(dash ? PLAY_SPRINT * GROUND / OWN_DASH_PACE : PLAY_JOG * GROUND / OWN_JOG_PACE);
					}
					return state.setAndContinue(dash ? RUN : JOG);
				}
				return state.setAndContinue(WALK);
			}
			calmPose = true;
			idling = true;
			RawAnimation fidget = idleFidget(state, wasIdle);
			return state.setAndContinue(fidget != null ? fidget : IDLE);
		}));
		// Sitting: her sitting idle on top of the sit pose (it keys what it moves; the sit pose keeps the rest). With a
		// "sit_idle_intro" (sitting down, pouring the tea), that plays once first, then the loop starts from its last
		// frame with no blend. Switched here rather than queued: GeckoLib's play-then-loop sags toward rest between.
		controllers.add(new AnimationController<>(this, "sitting", SIT_BLEND, state -> {
			if (menuAnimation == null && isInSittingPose() && hasAnimation("animation.companion.sit_idle")) {
				var baked = bakedAnimations();
				var intro = baked == null ? null : baked.getAnimation("animation.companion.sit_idle_intro");
				// Its blend in delays its start, so it ends about SIT_BLEND ticks after its length; a little late only holds
				// its last frame.
				if (intro != null && sittingTicks <= intro.length() + SIT_BLEND + 1) {
					if (!sitIntroPlayed) {
						sitIntroPlayed = true;
						state.getController().setTransitionLength(SIT_BLEND);
					}
					return state.setAndContinue(SIT_IDLE_INTRO);
				}
				if (sitIntroPlayed) {
					state.getController().setTransitionLength(0);
				}
				return state.setAndContinue(SIT_IDLE);
			}
			sitIntroPlayed = false;
			state.getController().setTransitionLength(SIT_BLEND);
			return PlayState.STOP;
		}));
	}

	/** Client side: her animation file, baked (null until it's loaded). */
	@Nullable
	protected software.bernie.geckolib.loading.object.BakedAnimations bakedAnimations() {
		var file = new net.minecraft.resources.ResourceLocation(com.freshorangeslices.enshining.Enshining.MODID, "animations/entity/"
				+ net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(getType()).getPath() + ".animation.json");
		return software.bernie.geckolib.cache.GeckoLibCache.getBakedAnimations().get(file);
	}

	/** Client side: whether her animation file has this animation. */
	protected boolean hasAnimation(String name) {
		var baked = bakedAnimations();
		return baked != null && baked.getAnimation(name) != null;
	}

	// Client side: standing idle, now and then she plays one of her extra idles ("..._idle_<anything>"), once.
	private boolean idling;
	@Nullable
	private RawAnimation fidget;
	private String lastFidget = "";
	private int nextFidgetAt;
	/** About every 5 minutes of standing idle (4 to 6). */
	private static final int FIDGET_EVERY = 20 * 60 * 4;

	/**
	 * After 4 to 6 minutes of standing idle, one of the animations named "animation.companion.idle_..." or
	 * "animation.NAME.idle_..." in her animation file (a different one from last time when there are several),
	 * played once, then her plain idle again until the next one. Null: the plain idle.
	 */
	@Nullable
	private RawAnimation idleFidget(software.bernie.geckolib.core.animation.AnimationState<?> state, boolean wasIdle) {
		if (!wasIdle) {
			fidget = null;
			nextFidgetAt = tickCount + FIDGET_EVERY + random.nextInt(FIDGET_EVERY / 2);
		}
		if (fidget != null) {
			if (!state.isCurrentAnimation(fidget) || !state.getController().hasAnimationFinished()) {
				return fidget;
			}
			fidget = null;
			nextFidgetAt = tickCount + FIDGET_EVERY + random.nextInt(FIDGET_EVERY / 2);
		}
		if (tickCount < nextFidgetAt) {
			return null;
		}
		nextFidgetAt = tickCount + FIDGET_EVERY + random.nextInt(FIDGET_EVERY / 2);
		var baked = bakedAnimations();
		if (baked == null) {
			return null;
		}
		java.util.List<String> extras = baked.animations().keySet().stream()
				.filter(name -> name.matches("animation\\.[a-z0-9_]+\\.idle_.+")).sorted().toList();
		if (extras.isEmpty()) {
			return null;
		}
		java.util.List<String> fresh = extras.size() > 1 ? extras.stream().filter(n -> !n.equals(lastFidget)).toList() : extras;
		lastFidget = fresh.get(random.nextInt(fresh.size()));
		fidget = RawAnimation.begin().thenPlay(lastFidget);
		return fidget;
	}

	/**
	 * The combo on the client: a new hit restarts its animation, which then holds on its last frame through the
	 * window (unless she's moving, then the legs take over); the ending plays once. Null means no combo is showing.
	 */
	@Nullable
	/** Client side: the combo phase the main controller is showing (a hit 1..n, or the ending). */
	private int comboShownPhase;

	private PlayState comboState(software.bernie.geckolib.core.animation.AnimationState<CompanionEntity> state) {
		int packed = entityData.get(COMBO);
		int phase = packed & 7;
		int count = packed >>> 3;
		if (phase == 0) {
			comboSeen = count;
			comboShownPhase = 0;
			return null;
		}
		RawAnimation animation = phase == COMBO_END_PHASE ? comboEndAnimation()
				: comboHits().get(Math.min(phase, comboHits().size()) - 1).animation();
		if (animation == null) {
			return null;
		}
		var controller = state.getController();
		if (count != comboSeen) {
			// Her ending waits for the last hit to finish on screen. The server starts the ending on its own clock, 0.75 s
			// after the hit; the hit started here a few ticks later (the update's trip, then its blend in), so switching
			// at once cut off its downward chop (hit 4 looked like a sweep that jumped to the sword on the floor).
			boolean hitStillPlaying = comboShownPhase >= 1 && comboShownPhase <= comboHits().size()
					&& !controller.hasAnimationFinished() && controller.getAnimationState() != AnimationController.State.PAUSED;
			if (phase == COMBO_END_PHASE && hitStillPlaying) {
				return PlayState.CONTINUE;
			}
			comboSeen = count;
			comboShownPhase = phase;
			// Start the blend into the next hit from the same pose counted within one turn, so a pose like 240 degrees
			// meets a hit starting at -120 (the same angle) without the sword whirling all the way round.
			unwindSnapshots();
			controller.setAnimationSpeed(comboSpeed());
			controller.setTransitionLength(2); // hits snap in
			controller.forceAnimationReset();
			return state.setAndContinue(animation);
		}
		// A hit holds its last frame (GeckoLib pauses there rather than stopping): that counts as done, so walking takes
		// over from a held pose instead of her sliding along in it.
		boolean done = !state.isCurrentAnimation(animation) || controller.hasAnimationFinished()
				|| controller.getAnimationState() == AnimationController.State.PAUSED;
		if (phase == COMBO_END_PHASE ? done : (done && state.isMoving()) || !state.isCurrentAnimation(animation)) {
			comboShownPhase = 0;   // the combo isn't on screen any more (walking took over): nothing to wait for
			return null;
		}
		return PlayState.CONTINUE;
	}

	/**
	 * GeckoLib blends from each bone's last pose to the next animation's first frame by its raw numbers, so a bone at
	 * 240 degrees would swing 360 the long way round to reach -120. Bring every bone's last pose within one turn of
	 * its rest (same look, smaller numbers) right before a blend starts; animation starts are kept within one turn too.
	 */
	private void unwindSnapshots() {
		var snapshots = getAnimatableInstanceCache().getManagerForId(getId()).getBoneSnapshotCollection();
		for (BoneSnapshot snapshot : snapshots.values()) {
			BoneSnapshot rest = snapshot.getBone().getInitialSnapshot();
			snapshot.updateRotation(rest.getRotX() + unwind(snapshot.getRotX() - rest.getRotX()),
					rest.getRotY() + unwind(snapshot.getRotY() - rest.getRotY()),
					rest.getRotZ() + unwind(snapshot.getRotZ() - rest.getRotZ()));
		}
	}

	private static float unwind(float radians) {
		return (float) (radians - Mth.TWO_PI * Math.round(radians / Mth.TWO_PI));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
