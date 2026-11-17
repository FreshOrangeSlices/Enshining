package com.freshorangeslices.enshining.core;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Game events the companions listen to (Forge bus). */
public final class CompanionEvents {
	private CompanionEvents() {
	}

	/**
	 * A companion that lands the killing blow earns XP: half the victim's max health, at least 1. If that was the
	 * last enemy around (8 blocks), she smiles.
	 */
	@SubscribeEvent
	public static void onDeath(LivingDeathEvent event) {
		LivingEntity victim = event.getEntity();
		if (!victim.level().isClientSide && event.getSource().getEntity() instanceof CompanionEntity companion && companion.isTame()) {
			companion.addXp(Math.max(1, Math.round(victim.getMaxHealth() / 2)));
			boolean more = !companion.level().getEntitiesOfClass(Mob.class, companion.getBoundingBox().inflate(8.0),
					mob -> mob != victim && mob.isAlive() && mob instanceof Enemy).isEmpty();
			if (!more) {
				companion.level().broadcastEntityEvent(companion, CompanionEntity.EVENT_HAPPY);
			}
		}
	}
}
