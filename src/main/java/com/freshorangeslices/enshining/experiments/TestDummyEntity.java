package com.freshorangeslices.enshining.experiments;

import com.freshorangeslices.enshining.core.CompanionEntity;
import com.freshorangeslices.enshining.core.Element;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.core.animation.RawAnimation;

/**
 * The test dummy: a plain companion wearing the experiments model (docs/art/practice/experiments.bbmodel), for
 * trying things out in the game without touching a real character. Creative spawn egg only, never spawns on its
 * own; befriend it with an apple. No skill or burst, ordinary punches.
 */
public class TestDummyEntity extends CompanionEntity {
	private static final RawAnimation STILL = RawAnimation.begin().thenPlay("animation.companion.idle");

	public TestDummyEntity(EntityType<? extends TamableAnimal> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.25)
				.add(Attributes.ATTACK_DAMAGE, 2.0)
				.add(Attributes.FOLLOW_RANGE, 24.0);
	}

	@Override
	public Element element() {
		return Element.ANEMO;
	}

	@Override
	public Item gift() {
		return Items.APPLE;
	}

	@Override
	protected int skillCooldownTicks() {
		return 400;
	}

	@Override
	protected int burstCost() {
		return 1000;
	}

	@Override
	public void useSkill(LivingEntity target) {
	}

	@Override
	public void useBurst(LivingEntity target) {
	}

	@Override
	protected RawAnimation skillAnimation() {
		return STILL;
	}

	@Override
	protected RawAnimation burstAnimation() {
		return STILL;
	}
}
