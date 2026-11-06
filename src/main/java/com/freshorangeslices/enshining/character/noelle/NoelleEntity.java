package com.freshorangeslices.enshining.character.noelle;

import com.freshorangeslices.enshining.core.CompanionEntity;
import com.freshorangeslices.enshining.core.Element;
import com.freshorangeslices.enshining.core.Fx;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.core.animation.RawAnimation;

/**
 * Noelle, the Geo maid knight with a claymore.
 * <ul>
 * <li>Skill, Breastplate: a Geo shield (absorption) on her and her owner; while it lasts, her hits may heal them both.</li>
 * <li>Burst, Sweeping Time: for 15 seconds her armor turns into attack and her swings hit everything around the target.</li>
 * </ul>
 */
public class NoelleEntity extends CompanionEntity {
	public static final int SHIELD_TICKS = 240;
	public static final int BURST_TICKS = 300;

	private static final UUID BURST_ATTACK = UUID.fromString("5e3b1c2a-7d41-4c55-9a0e-0e1a1f000101");
	private static final EntityDataAccessor<Boolean> SWEEPING = SynchedEntityData.defineId(NoelleEntity.class, EntityDataSerializers.BOOLEAN);
	private static final RawAnimation SKILL = RawAnimation.begin().thenPlay("animation.noelle.skill");
	private static final RawAnimation BURST = RawAnimation.begin().thenPlay("animation.noelle.burst");

	private int shieldTicks;
	private int burstTicks;

	public NoelleEntity(EntityType<? extends TamableAnimal> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 30.0)
				.add(Attributes.MOVEMENT_SPEED, 0.3)
				.add(Attributes.ATTACK_DAMAGE, 6.0)
				.add(Attributes.ARMOR, 8.0)
				.add(Attributes.FOLLOW_RANGE, 24.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.4);
	}

	@Override
	public Element element() {
		return Element.GEO;
	}

	@Override
	public Item gift() {
		return Items.BREAD;
	}

	@Override
	protected int skillCooldownTicks() {
		return 400;
	}

	@Override
	protected int burstCost() {
		return 60;
	}

	public boolean shieldActive() {
		return shieldTicks > 0;
	}

	public boolean burstActive() {
		return burstTicks > 0;
	}

	/** Sweeping Time is on: synced so clients draw her claymore bigger. */
	public boolean sweeping() {
		return entityData.get(SWEEPING);
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		entityData.define(SWEEPING, false);
	}

	// ---- Breastplate: stone armor that bursts out (Geo damage around her), shields her and her owner, heals on hits ----

	@Override
	public void useSkill(LivingEntity target) {
		shieldTicks = SHIELD_TICKS;
		shield(this);
		LivingEntity owner = getOwner();
		if (owner != null && owner.distanceToSqr(this) < 16 * 16) {
			shield(owner);
		}
		geoBlast(3.0, 2.0F + (float) getAttributeValue(Attributes.ARMOR) * 0.4F);
		if (level() instanceof ServerLevel server) {
			Fx.shards(server, Blocks.GOLD_BLOCK.defaultBlockState(), position().add(0, 0.6, 0), 40, 0.8);
			Fx.ring(server, element().dust(), position().add(0, 0.1, 0), 3.0, 48);
		}
		sound(SoundEvents.SHIELD_BLOCK, 1.0F, 0.7F);
		sound(SoundEvents.AMETHYST_CLUSTER_PLACE, 1.0F, 0.8F);
	}

	private void shield(LivingEntity who) {
		// No potion swirls (Absorption's are big and blue, right in the owner's face); the icon still shows it.
		who.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, SHIELD_TICKS, 1, false, false, true), this);
		if (level() instanceof ServerLevel server) {
			Fx.sphere(server, element().dust(), who.position().add(0, who.getBbHeight() / 2, 0), 1.1, 60);
		}
	}

	private void shieldBreaks() {
		if (level() instanceof ServerLevel server) {
			Fx.shards(server, Blocks.GOLD_BLOCK.defaultBlockState(), position().add(0, 1, 0), 25, 0.5);
			LivingEntity owner = getOwner();
			if (owner != null && owner.distanceToSqr(this) < 16 * 16) {
				Fx.shards(server, Blocks.GOLD_BLOCK.defaultBlockState(), owner.position().add(0, 1, 0), 25, 0.5);
			}
		}
		sound(SoundEvents.AMETHYST_CLUSTER_BREAK, 1.0F, 0.9F);
	}

	// ---- Sweeping Time: a Geo blast, then 15 s of a bigger claymore, armor as attack and swings that hit all around ----

	@Override
	public void useBurst(LivingEntity target) {
		burstTicks = BURST_TICKS;
		entityData.set(SWEEPING, true);
		setTransientAttack(getAttributeValue(Attributes.ARMOR) * 0.5);
		geoBlast(4.0, 4.0F + (float) getAttributeValue(Attributes.ARMOR) * 0.6F);
		if (level() instanceof ServerLevel server) {
			Vec3 at = position();
			for (int i = 1; i <= 4; i++) {
				Fx.ring(server, element().dust(), at.add(0, 0.15 * i, 0), i * 1.0, 20 + i * 10);
			}
			server.sendParticles(ParticleTypes.FLASH, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
			Fx.shards(server, Blocks.GOLD_BLOCK.defaultBlockState(), at.add(0, 0.5, 0), 60, 1.5);
		}
		sound(SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.0F, 0.8F);
		sound(SoundEvents.ANVIL_LAND, 0.6F, 0.6F);
	}

	private void endBurst() {
		entityData.set(SWEEPING, false);
		setTransientAttack(0);
		sound(SoundEvents.BEACON_DEACTIVATE, 0.7F, 1.2F);
	}

	/** Geo damage to every enemy (not anyone's pet) within the radius. */
	private void geoBlast(double radius, float damage) {
		for (LivingEntity near : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(radius),
				e -> e != this && e instanceof Enemy && e.isAlive() && !(e instanceof TamableAnimal pet && pet.isTame()))) {
			near.hurt(damageSources().mobAttack(this), damage);
		}
	}

	private void setTransientAttack(double amount) {
		var attack = getAttribute(Attributes.ATTACK_DAMAGE);
		if (attack == null) {
			return;
		}
		attack.removeModifier(BURST_ATTACK);
		if (amount > 0) {
			attack.addTransientModifier(new AttributeModifier(BURST_ATTACK, "Sweeping Time", amount, AttributeModifier.Operation.ADDITION));
		}
	}

	@Override
	protected void onHit(LivingEntity target) {
		ServerLevel server = level() instanceof ServerLevel s ? s : null;
		// Every claymore hit: a heavy whoosh and a spark of impact.
		sound(SoundEvents.PLAYER_ATTACK_STRONG, 0.8F, 0.75F);
		if (server != null) {
			server.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.6), target.getZ(), 8, 0.3, 0.3, 0.3, 0.2);
		}

		if (shieldActive() && random.nextBoolean()) {
			heal(2.0F);
			LivingEntity owner = getOwner();
			if (owner != null && owner.distanceToSqr(this) < 16 * 16) {
				owner.heal(2.0F);
				if (server != null) {
					server.sendParticles(ParticleTypes.HEART, owner.getX(), owner.getY() + 2, owner.getZ(), 1, 0, 0, 0, 0);
				}
			}
			if (server != null) {
				server.sendParticles(ParticleTypes.HEART, getX(), getY() + 2.1, getZ(), 1, 0, 0, 0, 0);
			}
		}

		if (burstActive()) {
			float sweep = (float) getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5F;
			for (LivingEntity near : level().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(2.5),
					e -> e != target && e != this && e instanceof Enemy && e.isAlive())) {
				near.hurt(damageSources().mobAttack(this), sweep);
			}
			if (server != null) {
				Vec3 from = position().add(0, 1.0, 0);
				Fx.arc(server, element().dust(), from, getYRot(), 2.2, 160, 28, 0.5);
				Fx.arc(server, ParticleTypes.WAX_OFF, from, getYRot(), 2.0, 140, 10, 0.4);
				server.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY(0.5), target.getZ(), 3, 0.8, 0.2, 0.8, 0);
				Fx.shards(server, Blocks.GOLD_BLOCK.defaultBlockState(), target.position().add(0, target.getBbHeight() / 2, 0), 12, 0.4);
			}
			sound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.7F);
			sound(SoundEvents.AMETHYST_BLOCK_HIT, 1.0F, 0.8F);
		}
	}

	@Override
	protected void tickAbilities() {
		ServerLevel server = level() instanceof ServerLevel s ? s : null;
		if (shieldTicks > 0) {
			shieldTicks--;
			if (shieldTicks == 0) {
				shieldBreaks();
			} else if (shieldTicks % 4 == 0 && server != null) {
				// A shimmering gold shell on her and on her owner while it lasts.
				Fx.shimmer(server, element().dust(), position().add(0, getBbHeight() / 2, 0), 1.0, 5, random);
				LivingEntity owner = getOwner();
				if (owner != null && owner.distanceToSqr(this) < 16 * 16 && owner.getAbsorptionAmount() > 0) {
					Fx.shimmer(server, element().dust(), owner.position().add(0, owner.getBbHeight() / 2, 0), 1.0, 5, random);
				}
			}
		}
		if (burstTicks > 0) {
			burstTicks--;
			if (burstTicks == 0) {
				endBurst();
			} else if (burstTicks % 3 == 0 && server != null) {
				// A gold aura spiralling up around her.
				double angle = burstTicks * 0.6;
				for (int k = 0; k < 2; k++) {
					double a = angle + k * Math.PI;
					server.sendParticles(element().dust(), getX() + Math.cos(a) * 0.7, getY() + (burstTicks % 30) / 15.0, getZ() + Math.sin(a) * 0.7,
							1, 0, 0, 0, 0);
				}
				if (burstTicks % 9 == 0) {
					server.sendParticles(ParticleTypes.WAX_OFF, getX(), getY() + 1.2, getZ(), 2, 0.4, 0.6, 0.4, 0);
				}
			}
		}
	}

	@Override
	protected RawAnimation skillAnimation() {
		return SKILL;
	}

	@Override
	protected RawAnimation burstAnimation() {
		return BURST;
	}
}
