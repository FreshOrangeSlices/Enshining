package com.freshorangeslices.enshining.gametest;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.character.noelle.NoelleEntity;
import com.freshorangeslices.enshining.core.CompanionEntity;
import com.freshorangeslices.enshining.core.CompanionMode;
import com.freshorangeslices.enshining.registry.ModEntities;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** The companion core, through Noelle. */
@GameTestHolder(Enshining.MODID)
@PrefixGameTestTemplate(false)
public final class CompanionGameTests {
	private static ServerPlayer player(GameTestHelper helper) {
		ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "enshining-test"));
		BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
		player.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
		return player;
	}

	private static NoelleEntity noelle(GameTestHelper helper) {
		NoelleEntity noelle = helper.spawn(ModEntities.NOELLE.get(), new BlockPos(2, 2, 2));
		noelle.setNoAi(true);
		return noelle;
	}

	@GameTest(template = "empty")
	public static void wildNoelleHasHerStats(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		helper.assertFalse(noelle.isTame(), "A new Noelle is wild");
		helper.assertTrue(noelle.getMaxHealth() == 30.0F, "30 health: " + noelle.getMaxHealth());
		helper.assertTrue(noelle.getAttributeValue(Attributes.ARMOR) == 8.0, "8 armor");
		helper.assertTrue(noelle.companionLevel() == 1, "Level 1");
		helper.assertFalse(noelle.inCombat(), "Not in a fight, so no claymore");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void breadBefriendsNoelle(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		ServerPlayer player = player(helper);
		helper.assertFalse(noelle.mobInteract(player, InteractionHand.MAIN_HAND).consumesAction(), "Empty hands don't befriend her");

		for (int i = 0; i < 60 && !noelle.isTame(); i++) {
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
			noelle.mobInteract(player, InteractionHand.MAIN_HAND);
		}
		helper.assertTrue(noelle.isTame(), "Bread befriends her (1 in 3 a loaf)");
		helper.assertTrue(noelle.isOwnedBy(player), "She's the player's");
		helper.assertTrue(noelle.mode() == CompanionMode.FOLLOW, "And follows");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void emptyHandCyclesModes(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		ServerPlayer player = player(helper);
		noelle.befriend(player);
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

		noelle.mobInteract(player, InteractionHand.MAIN_HAND);
		helper.assertTrue(noelle.mode() == CompanionMode.WANDER, "Follow -> Wander");
		noelle.mobInteract(player, InteractionHand.MAIN_HAND);
		helper.assertTrue(noelle.mode() == CompanionMode.STAY && noelle.isOrderedToSit(), "Wander -> Stay (sits)");
		noelle.mobInteract(player, InteractionHand.MAIN_HAND);
		helper.assertTrue(noelle.mode() == CompanionMode.FOLLOW && !noelle.isOrderedToSit(), "Stay -> Follow");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void ownerHitsNeverLand(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		ServerPlayer player = player(helper);
		noelle.befriend(player);
		noelle.hurt(helper.getLevel().damageSources().playerAttack(player), 10.0F);
		helper.assertTrue(noelle.getHealth() == noelle.getMaxHealth(), "Her owner's swing didn't hurt her");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void levelsAddHealthAndAttack(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		noelle.befriend(player(helper));
		double attack = noelle.getAttributeValue(Attributes.ATTACK_DAMAGE);
		noelle.addXp(CompanionEntity.xpToNext(1) + CompanionEntity.xpToNext(2));
		helper.assertTrue(noelle.companionLevel() == 3, "Level 3: " + noelle.companionLevel());
		helper.assertTrue(noelle.getMaxHealth() == 34.0F, "34 health at level 3: " + noelle.getMaxHealth());
		helper.assertTrue(noelle.getAttributeValue(Attributes.ATTACK_DAMAGE) == attack + 1.0, "+1 attack");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void breastplateShieldsHer(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
		zombie.setNoAi(true);
		noelle.useSkill(zombie);
		helper.assertTrue(noelle.shieldActive(), "The shield is up");
		helper.assertTrue(noelle.getAbsorptionAmount() > 0, "Absorption from Breastplate: " + noelle.getAbsorptionAmount());
		helper.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "The stone armor bursting out hurt the zombie next to her: health " + zombie.getHealth() + "/" + zombie.getMaxHealth()
				+ ", distance " + Math.sqrt(noelle.distanceToSqr(zombie)) + ", invulnerable " + zombie.isInvulnerable() + ", alive " + zombie.isAlive()
				+ ", difficulty " + helper.getLevel().getDifficulty());
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void sweepingTimeTurnsArmorIntoAttack(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 4));
		zombie.setNoAi(true);
		double before = noelle.getAttributeValue(Attributes.ATTACK_DAMAGE);
		noelle.useBurst(zombie);
		helper.assertTrue(noelle.burstActive() && noelle.sweeping(), "Sweeping Time is on (and synced)");
		helper.assertTrue(noelle.getAttributeValue(Attributes.ATTACK_DAMAGE) == before + 4.0, "Half her 8 armor as attack");
		helper.succeed();
	}

	@GameTest(template = "empty", timeoutTicks = 200)
	public static void befriendedNoelleFightsAZombie(GameTestHelper helper) {
		NoelleEntity noelle = helper.spawn(ModEntities.NOELLE.get(), new BlockPos(1, 2, 1));
		noelle.befriend(player(helper));
		// A husk with its AI on (one with no AI floats where it spawned, out of reach; a husk doesn't burn in daylight).
		Zombie zombie = helper.spawn(EntityType.HUSK, new BlockPos(4, 2, 4));
		zombie.setPersistenceRequired();
		float start = zombie.getHealth();
		helper.succeedWhen(() -> {
			helper.assertTrue(noelle.getTarget() == zombie, "Noelle targets the zombie: " + noelle.getTarget());
			helper.assertTrue(noelle.inCombat(), "She's in a fight (her claymore shows)");
			helper.assertTrue(zombie.getHealth() < start || !zombie.isAlive(), "And hits it. Noelle at " + noelle.position() + ", zombie at "
					+ zombie.position() + ", distance " + Math.sqrt(noelle.distanceToSqr(zombie)) + ", sitting " + noelle.isInSittingPose()
					+ ", mode " + noelle.mode() + ", running " + noelle.goalSelectorRunning() + ", path " + noelle.getNavigation().getPath());
		});
	}

	@GameTest(template = "empty", timeoutTicks = 200)
	public static void wildNoelleFightsBack(GameTestHelper helper) {
		NoelleEntity noelle = helper.spawn(ModEntities.NOELLE.get(), new BlockPos(1, 2, 1));
		Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
		zombie.setNoAi(true);
		zombie.setPersistenceRequired();
		// After a few ticks: a hit on an entity's very first tick doesn't count for retaliation in vanilla.
		helper.runAfterDelay(5, () -> noelle.hurt(helper.getLevel().damageSources().mobAttack(zombie), 1.0F));
		helper.succeedWhen(() -> helper.assertTrue(noelle.getTarget() == zombie, "A wild Noelle hits back: " + noelle.getTarget()));
	}
}
