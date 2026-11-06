package com.freshorangeslices.enshining.core;

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

	private static final UUID LEVEL_HEALTH = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000001");
	private static final UUID LEVEL_ATTACK = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000002");

	protected static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.companion.idle");
	protected static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.companion.walk");
	protected static final RawAnimation SIT = RawAnimation.begin().thenLoop("animation.companion.sit");
	protected static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.companion.attack");

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
	private int xp;
	private int skillCooldown;
	private int energy;

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

	/** Called after each landed melee hit, for effects that ride on hits (heals, sweeps). */
	protected void onHit(LivingEntity target) {
	}

	/** Called every server tick, for timers a skill or burst started. */
	protected void tickAbilities() {
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
		goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.15, true));
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
		triggerAnim("action", "attack");
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

		LivingEntity target = getTarget();
		if (target == null || !target.isAlive() || distanceToSqr(target) > 36) {
			return;
		}
		if (energy >= burstCost()) {
			energy = 0;
			triggerAnim("action", "burst");
			useBurst(target);
		} else if (skillCooldown <= 0) {
			skillCooldown = skillCooldownTicks();
			energy = Math.min(MAX_ENERGY, energy + 6);
			triggerAnim("action", "skill");
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

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "movement", 5, state -> {
			if (isInSittingPose()) {
				return state.setAndContinue(SIT);
			}
			return state.setAndContinue(state.isMoving() ? WALK : IDLE);
		}));
		controllers.add(new AnimationController<>(this, "action", 2, state -> PlayState.STOP)
				.triggerableAnim("attack", ATTACK)
				.triggerableAnim("skill", skillAnimation())
				.triggerableAnim("burst", burstAnimation()));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
