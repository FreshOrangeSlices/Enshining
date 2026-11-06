package com.freshorangeslices.enshining.character.noelle;

import com.freshorangeslices.enshining.core.CompanionEntity;
import com.freshorangeslices.enshining.core.Element;
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

	@Override
	public void useSkill(LivingEntity target) {
		shieldTicks = SHIELD_TICKS;
		shield(this);
		LivingEntity owner = getOwner();
		if (owner != null && owner.distanceToSqr(this) < 16 * 16) {
			shield(owner);
		}
		sound(SoundEvents.ANVIL_LAND, 0.35F, 1.6F);
	}

	private void shield(LivingEntity who) {
		who.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, SHIELD_TICKS, 1), this);
		ring(element().dust(), who.getX(), who.getY() + 0.1, who.getZ(), 1.0, 24);
		ring(element().dust(), who.getX(), who.getY() + 1.0, who.getZ(), 0.8, 16);
	}

	@Override
	public void useBurst(LivingEntity target) {
		burstTicks = BURST_TICKS;
		double armor = getAttributeValue(Attributes.ARMOR);
		setTransientAttack(armor * 0.5);
		sound(SoundEvents.ANVIL_USE, 0.5F, 0.7F);
		ring(element().dust(), getX(), getY() + 0.2, getZ(), 2.0, 40);
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
		if (shieldActive() && random.nextBoolean()) {
			heal(2.0F);
			LivingEntity owner = getOwner();
			if (owner != null && owner.distanceToSqr(this) < 16 * 16) {
				owner.heal(2.0F);
				if (level() instanceof ServerLevel server) {
					server.sendParticles(ParticleTypes.HEART, owner.getX(), owner.getY() + 2, owner.getZ(), 1, 0, 0, 0, 0);
				}
			}
		}
		if (burstActive()) {
			float sweep = (float) getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5F;
			for (LivingEntity near : level().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(2.5),
					e -> e != target && e != this && e instanceof Enemy && e.isAlive())) {
				near.hurt(damageSources().mobAttack(this), sweep);
			}
			if (level() instanceof ServerLevel server) {
				server.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY(0.5), target.getZ(), 2, 0.6, 0.2, 0.6, 0);
				server.sendParticles(element().dust(), target.getX(), target.getY(0.5), target.getZ(), 12, 1.0, 0.3, 1.0, 0);
			}
		}
	}

	@Override
	protected void tickAbilities() {
		if (shieldTicks > 0) {
			shieldTicks--;
			if (shieldTicks % 10 == 0 && level() instanceof ServerLevel server) {
				server.sendParticles(element().dust(), getX(), getY() + 1, getZ(), 2, 0.4, 0.5, 0.4, 0);
			}
		}
		if (burstTicks > 0) {
			burstTicks--;
			if (burstTicks == 0) {
				setTransientAttack(0);
			} else if (burstTicks % 5 == 0 && level() instanceof ServerLevel server) {
				server.sendParticles(element().dust(), getX(), getY() + 0.8, getZ(), 3, 0.5, 0.6, 0.5, 0);
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
