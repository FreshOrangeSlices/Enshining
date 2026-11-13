package com.freshorangeslices.enshining.core;

import java.util.EnumSet;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

/** Out of a fight, if her owner stands right on top of her for 2 seconds, she steps a couple of blocks aside. */
public class PersonalSpaceGoal extends Goal {
	private static final double TOO_CLOSE = 1.0;
	private static final int PATIENCE_TICKS = 40;

	private final CompanionEntity companion;
	private int crowdedTicks;

	public PersonalSpaceGoal(CompanionEntity companion) {
		this.companion = companion;
		setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		LivingEntity owner = companion.getOwner();
		if (owner == null || companion.isOrderedToSit() || companion.inCombat() || companion.getTarget() != null
				|| owner.isPassenger() || companion.distanceToSqr(owner) > TOO_CLOSE * TOO_CLOSE) {
			crowdedTicks = 0;
			return false;
		}
		return ++crowdedTicks >= PATIENCE_TICKS;
	}

	@Override
	public boolean canContinueToUse() {
		return companion.getNavigation().isInProgress() && !companion.inCombat();
	}

	@Override
	public void start() {
		crowdedTicks = 0;
		LivingEntity owner = companion.getOwner();
		if (owner == null) {
			return;
		}
		Vec3 spot = DefaultRandomPos.getPosAway(companion, 4, 2, owner.position());
		if (spot != null) {
			companion.getNavigation().moveTo(spot.x, spot.y, spot.z, 0.8);
		}
	}
}
