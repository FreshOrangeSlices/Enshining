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
	/** The combo, packed: (count of hits started << 3) | phase. Phase 0 = none, 1..n = combo hit n, 7 = the ending. */
	private static final EntityDataAccessor<Integer> COMBO = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	private static final int COMBO_END_PHASE = 7;
	/** How long (ticks) she lingers on a combo hit's last frame waiting for the next one before the combo resets. */
	private static final int COMBO_WINDOW_TICKS = 20;
	public static final int ACTION_NONE = 0;
	public static final int ACTION_SKILL = 1;
	public static final int ACTION_BURST = 2;

	private static final UUID LEVEL_HEALTH = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000001");
	private static final UUID LEVEL_ATTACK = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000002");

	protected static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.companion.idle");
	protected static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.companion.walk");
	protected static final RawAnimation SIT = RawAnimation.begin().thenLoop("animation.companion.sit");
	protected static final RawAnimation RUN = RawAnimation.begin().thenLoop("animation.companion.run");
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
	// Combo, server side
	private int comboNext;        // which hit comes next (0-based)
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
	public abstract void useSkill(LivingEntity target);

	/** The burst, used in a fight once energy is full enough. */
	public abstract void useBurst(LivingEntity target);

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

	private float lookBlend = 1.0F;  // client side: how much the head looks at things (0 in a fight), eased per tick
	private float lookBlendO = 1.0F;

	/** Client side: 0..1, how much the head should follow the look direction this frame (eases in and out). */
	public float lookBlend(float partialTick) {
		return Mth.lerp(partialTick, lookBlendO, lookBlend);
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

	/** Server side: whether she's free to start the next combo hit (none playing, no skill or burst). */
	public boolean canStartComboHit() {
		if (comboHits().isEmpty() || action() != ACTION_NONE) {
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
		ComboHit hit = hits.get(index);
		comboNext = (index + 1) % hits.size();
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
		double dx = target.getX() - getX();
		double dz = target.getZ() - getZ();
		// Aim at the target, but turn there quickly over the first ticks instead of snapping (a snap reads as a spin).
		comboAimYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		comboYaw = yBodyRot;
		lockFacing();
		entityData.set(COMBO, comboCount << 3 | (index + 1));
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
		if (level().isClientSide) {
			lookBlendO = lookBlend;
			float want = playingMove || inCombat() ? 0.0F : 1.0F;
			lookBlend += Mth.clamp(want - lookBlend, -0.1F, 0.1F); // half a second to ease the look in or out
		}
		// Locked facing for the whole of a hit: the AI's look/turn this tick is undone before it's sent to players.
		if (!level().isClientSide && comboPieceTicks > 0) {
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
				LivingEntity target = comboFoe(0.5); // a little give
				if (target != null) {
					doHurtTarget(target);
				}
			}
		}
		if (comboPieceTicks > 0 && --comboPieceTicks == 0) {
			LivingEntity next = comboFoe(0.0);
			if (next != null && action() == ACTION_NONE) {
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
		// Holding a pose or in the ending: anything hostile stepping into reach picks the combo back up.
		if ((comboWindowTicks > 0 || comboEndTicks > 0) && canStartComboHit()) {
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
		if (comboPieceTicks > 0 && comboCancelTicks <= 0 && comboImpactIndex >= comboImpacts.length && canStartComboHit()) {
			// Animation cancel onto whoever is in reach (her target first), not only the one she started on.
			LivingEntity next = comboFoe(0.0);
			if (next != null && next != comboTarget) {
				setTarget(next);
				startComboHit(next);
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
		if (!isTame()) {
			return null;
		}
		double reach = meleeReach() * meleeReachScale() + 1.0 + give;
		return level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(reach), mob -> mob instanceof Enemy && mob.isAlive()
						&& !(mob instanceof TamableAnimal tame && tame.isTame()) && inMeleeReach(mob, give) && canAttack(mob))
				.stream().min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
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
			}
			if (getOwner() instanceof Player owner) {
				owner.displayClientMessage(Component.translatable("enshining.companion.level_up", getDisplayName(), companionLevel()), true);
			}
		}
	}

	private void applyLevelStats() {
		int bonusLevels = companionLevel() - 1;
		setModifier(Attributes.MAX_HEALTH, LEVEL_HEALTH, "Companion level health", bonusLevels * 2.0);
		setModifier(Attributes.ATTACK_DAMAGE, LEVEL_ATTACK, "Companion level attack", bonusLevels * 0.5);
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

		targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));
		targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));
		targetSelector.addGoal(3, new HurtByTargetGoal(this));
		targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
				mob -> isTame() && !isOrderedToSit() && !(mob instanceof Creeper)));
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
		boolean comboActive = comboPieceTicks > 0 || comboWindowTicks > 0 || comboEndTicks > 0;
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
		if (getTarget() != null && getTarget().isAlive() || getLastHurtByMob() != null && tickCount - getLastHurtByMobTimestamp() < 60) {
			combatTicks = 60;
		} else if (combatTicks > 0) {
			combatTicks--;
		}
		entityData.set(COMBAT, combatTicks > 0);
		// Sprinting (synced) while chasing or catching up, so clients pick the run animation; strolling stays a walk.
		setSprinting(!comboActive && getNavigation().isInProgress() && getMoveControl().getSpeedModifier() >= 1.05);

		LivingEntity target = getTarget();
		if (target == null || !target.isAlive() || distanceToSqr(target) > 36 || comboPieceTicks > 0) {
			return;
		}
		if (energy >= burstCost()) {
			energy = 0;
			playAction(ACTION_BURST, burstAnimationTicks());
			useBurst(target);
		} else if (skillCooldown <= 0) {
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

		if (stack.isEmpty() && hand == InteractionHand.MAIN_HAND) {
			if (player.isShiftKeyDown()) {
				player.displayClientMessage(Component.translatable("enshining.companion.status", getDisplayName(), companionLevel(),
						xp, xpToNext(companionLevel()), energy, Component.translatable("enshining.mode." + mode().name().toLowerCase())), true);
			} else {
				setMode(mode().next());
				player.displayClientMessage(Component.translatable("enshining.companion.mode", getDisplayName(),
						Component.translatable("enshining.mode." + mode().name().toLowerCase())), true);
			}
			return InteractionResult.SUCCESS;
		}

		return InteractionResult.PASS;
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
			int action = action();
			playingMove = true;
			if (action == ACTION_BURST) {
				return state.setAndContinue(burstAnimation());
			}
			if (action == ACTION_SKILL) {
				return state.setAndContinue(skillAnimation());
			}
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
				return state.setAndContinue(isSprinting() ? RUN : WALK);
			}
			return state.setAndContinue(IDLE);
		}));
	}

	/**
	 * The combo on the client: a new hit restarts its animation, which then holds on its last frame through the
	 * window (unless she's moving, then the legs take over); the ending plays once. Null means no combo is showing.
	 */
	@Nullable
	private PlayState comboState(software.bernie.geckolib.core.animation.AnimationState<CompanionEntity> state) {
		int packed = entityData.get(COMBO);
		int phase = packed & 7;
		int count = packed >>> 3;
		if (phase == 0) {
			comboSeen = count;
			return null;
		}
		RawAnimation animation = phase == COMBO_END_PHASE ? comboEndAnimation() : comboHits().get(Math.min(phase, comboHits().size()) - 1).animation();
		if (animation == null) {
			return null;
		}
		var controller = state.getController();
		if (count != comboSeen) {
			comboSeen = count;
			// Start the blend into the next hit from the same pose counted within one turn, so a pose like 240 degrees
			// meets a hit starting at -120 (the same angle) without the sword whirling all the way round.
			unwindSnapshots();
			controller.setAnimationSpeed(comboSpeed());
			controller.setTransitionLength(2); // hits snap in
			controller.forceAnimationReset();
			return state.setAndContinue(animation);
		}
		boolean done = !state.isCurrentAnimation(animation) || controller.hasAnimationFinished();
		if (phase == COMBO_END_PHASE ? done : (done && state.isMoving()) || !state.isCurrentAnimation(animation)) {
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
