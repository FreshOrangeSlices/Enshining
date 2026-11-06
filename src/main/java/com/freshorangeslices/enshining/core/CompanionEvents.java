package com.freshorangeslices.enshining.core;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Game events the companions listen to (Forge bus). */
public final class CompanionEvents {
	private CompanionEvents() {
	}

	/** A companion that lands the killing blow earns XP: half the victim's max health, at least 1. */
	@SubscribeEvent
	public static void onDeath(LivingDeathEvent event) {
		LivingEntity victim = event.getEntity();
		if (!victim.level().isClientSide && event.getSource().getEntity() instanceof CompanionEntity companion && companion.isTame()) {
			companion.addXp(Math.max(1, Math.round(victim.getMaxHealth() / 2)));
		}
	}
}
