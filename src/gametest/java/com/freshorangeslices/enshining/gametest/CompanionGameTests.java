package com.freshorangeslices.enshining.gametest;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.character.noelle.NoelleEntity;
import com.freshorangeslices.enshining.core.CompanionBehavior;
import com.freshorangeslices.enshining.core.CompanionEntity;
import com.freshorangeslices.enshining.core.CompanionMenu;
import com.freshorangeslices.enshining.core.CompanionMode;
import com.freshorangeslices.enshining.experiments.TestDummyEntity;
import com.freshorangeslices.enshining.play.PlayAs;
import com.freshorangeslices.enshining.registry.ModEntities;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
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
	public static void testDummyIsAPlainCompanion(GameTestHelper helper) {
		var dummy = helper.spawn(ModEntities.TEST_DUMMY.get(), new BlockPos(2, 2, 2));
		ServerPlayer player = player(helper);
		for (int i = 0; i < 60 && !dummy.isTame(); i++) {
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE));
			dummy.mobInteract(player, InteractionHand.MAIN_HAND);
		}
		helper.assertTrue(dummy.getMaxHealth() == 20.0F, "20 health: " + dummy.getMaxHealth());
		helper.assertTrue(dummy.isTame(), "Apples befriend the test dummy");
		helper.succeed();
	}

	// ---- Playing as her ---------------------------------------------------------------------------------------

	@GameTest(template = "empty")
	public static void playingAsHerTakesTheHitsForYou(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		ServerPlayer player = player(helper);
		noelle.befriend(player);
		helper.assertTrue(PlayAs.start(player, noelle), "You can play as her");
		helper.assertTrue(player.getVehicle() == noelle, "You're inside her");
		helper.assertTrue(noelle.getControllingPassenger() == player, "Your keys steer her");
		Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 2));
		zombie.setNoAi(true);
		float before = noelle.getHealth();
		helper.assertTrue(PlayAs.shield(player, helper.getLevel().damageSources().mobAttack(zombie), 4.0F), "The hit never lands on Steve");
		helper.assertTrue(noelle.getHealth() < before, "It lands on her: " + noelle.getHealth() + " of " + before);
		PlayAs.hopOut(player);
		helper.assertFalse(player.isPassenger(), "J: you're Steve again");
		helper.assertFalse(PlayAs.shield(player, helper.getLevel().damageSources().mobAttack(zombie), 4.0F), "Steve takes his own hits again");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void numberKeysSwapWhoYouAre(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		ServerPlayer player = player(helper);
		noelle.befriend(player);
		TestDummyEntity dummy = helper.spawn(ModEntities.TEST_DUMMY.get(), new BlockPos(4, 2, 4));
		dummy.setNoAi(true);
		dummy.befriend(player);
		helper.assertTrue(PlayAs.start(player, noelle), "Play as Noelle");
		helper.assertTrue(dummy.isOffField(), "The rest of the party goes off-field");
		int slot = PlayAs.party(player).indexOf(dummy);
		helper.assertTrue(slot >= 0 && PlayAs.party(player).size() == 2, "Both in the party: " + PlayAs.party(player).size());
		PlayAs.switchTo(player, slot);
		helper.assertTrue(player.getVehicle() == dummy, "Now you're the dummy");
		helper.assertTrue(noelle.isOffField() && !dummy.isOffField(), "Noelle off-field, the dummy on");
		helper.assertTrue(dummy.distanceTo(noelle) < 0.01F, "The dummy took her place: " + dummy.distanceTo(noelle));
		PlayAs.hopOut(player);
		helper.assertFalse(player.isPassenger(), "J: Steve again");
		helper.assertFalse(noelle.isOffField() || dummy.isOffField(), "Everyone's back");
		helper.succeed();
	}

	/** Playing as her with her AI running (as in game: it stands down while you drive her), facing south (+z). */
	private static NoelleEntity playedNoelle(GameTestHelper helper, ServerPlayer player) {
		NoelleEntity noelle = noelle(helper);
		noelle.setNoAi(false);
		noelle.befriend(player);
		PlayAs.start(player, noelle);
		noelle.setYRot(0.0F);
		noelle.setYBodyRot(0.0F);
		return noelle;
	}

	@GameTest(template = "empty", timeoutTicks = 60)
	public static void leftClickSwingsHerCombo(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		var husk = helper.spawn(EntityType.HUSK, new BlockPos(2, 2, 4));
		husk.setNoAi(true);
		float full = husk.getHealth();
		noelle.playerAttack();
		helper.assertTrue(noelle.comboNextForTests() == 1, "The click starts hit 1");
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(husk.getHealth() < full, "Hit 1 lands on what's in front of her: " + husk.getHealth());
			helper.succeed();
		});
	}

	@GameTest(template = "empty", timeoutTicks = 60)
	public static void aShortHoldGoesStraightToTheFinish(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		noelle.playerChargeBegin();
		noelle.playerChargeEnd();
		helper.assertTrue(noelle.chargeStageForTests() == 1, "Wind-up");
		helper.runAfterDelay(23, () -> {
			helper.assertTrue(noelle.chargeStageForTests() == 3, "Let go in the wind-up: no spin, the finish: " + noelle.chargeStageForTests());
			helper.succeed();
		});
	}

	@GameTest(template = "empty", timeoutTicks = 100)
	public static void aLongHoldSpinsUntilYouLetGo(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		noelle.playerChargeBegin();
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(noelle.chargeStageForTests() == 2, "Still held: spinning: " + noelle.chargeStageForTests());
			noelle.playerChargeEnd();
		});
		helper.runAfterDelay(62, () -> {
			helper.assertTrue(noelle.chargeStageForTests() == 3, "Let go: the finish at the end of the spin: " + noelle.chargeStageForTests());
			helper.succeed();
		});
	}

	@GameTest(template = "empty")
	public static void eCastsHerSkillAndQNeedsEnergy(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		noelle.playerBurst();
		helper.assertTrue(noelle.action() == CompanionEntity.ACTION_NONE, "Q with no energy: nothing");
		noelle.playerSkill();
		helper.assertTrue(noelle.action() == CompanionEntity.ACTION_SKILL, "E: her skill");
		int cooldown = noelle.skillCooldown();
		helper.assertTrue(cooldown > 0, "Then it's on cooldown");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void aDashOrJumpRestartsTheCombo(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		noelle.playerAttack();
		helper.assertTrue(noelle.comboNextForTests() == 1, "Hit 1 playing");
		noelle.playerCancel();
		helper.assertTrue(noelle.comboNextForTests() == 0, "Cut short: the next click is hit 1 again");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void xChoosesWhatHerHitsLandOn(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		var husk = helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 4));
		var pig = helper.spawn(EntityType.PIG, new BlockPos(3, 2, 4));
		var villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 5));
		helper.assertTrue(noelle.isFoe(husk) && !noelle.isFoe(pig) && !noelle.isFoe(villager), "Monsters only, to start");
		PlayAs.targets(player, CompanionEntity.TARGETS_NOT_FRIENDLY);
		helper.runAfterDelay(1, () -> {
			helper.assertTrue(noelle.isFoe(husk) && noelle.isFoe(pig), "X once: animals too");
			helper.assertFalse(noelle.isFoe(villager) || noelle.isFoe(player), "But not friendlies or players");
			PlayAs.targets(player, CompanionEntity.TARGETS_EVERYTHING);
			helper.runAfterDelay(1, () -> {
				helper.assertTrue(noelle.isFoe(husk) && noelle.isFoe(pig) && noelle.isFoe(villager), "X twice: everything");
				helper.assertFalse(noelle.isFoe(player), "Never players");
				PlayAs.hopOut(player);
				helper.assertFalse(noelle.isFoe(pig) || noelle.isFoe(villager), "Back on her own, monsters only");
				helper.succeed();
			});
		});
	}

	@GameTest(template = "empty", timeoutTicks = 60)
	public static void aClickMidHitWaitsForThatHitToLand(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		noelle.playerAttack();
		// Mashing: a second click right away is kept (hit 1 swings through, about 15 ticks), then hit 2 follows.
		helper.runAfterDelay(2, () -> {
			noelle.playerAttack();
			helper.assertTrue(noelle.comboNextForTests() == 1, "Hit 1 isn't cut short");
		});
		helper.runAfterDelay(21, () -> {
			helper.assertTrue(noelle.comboNextForTests() == 2, "The kept click became hit 2: " + noelle.comboNextForTests());
			helper.succeed();
		});
	}

	@GameTest(template = "empty")
	public static void onlyTheDashGivesIFramesNotTheSprint(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		PlayAs.input(player, true, true, true, false);
		helper.runAfterDelay(1, () -> {
			helper.assertFalse(noelle.hurt(helper.getLevel().damageSources().generic(), 2.0F), "Dashing: i-frames");
			PlayAs.input(player, true, true, false, false);
			helper.runAfterDelay(1, () -> {
				helper.assertTrue(noelle.hurt(helper.getLevel().damageSources().generic(), 2.0F), "Sprinting after it: no i-frames");
				helper.succeed();
			});
		});
	}

	@GameTest(template = "empty")
	public static void aDashCutsTheChargedAttack(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		noelle.playerChargeBegin();
		helper.assertTrue(noelle.chargeStageForTests() == 1, "Winding up");
		noelle.playerCancel();
		helper.assertTrue(noelle.chargeStageForTests() == 0, "A dash cuts the charged attack");
		helper.succeed();
	}

	@GameTest(template = "empty", timeoutTicks = 120)
	public static void playedComboWaitsFourSeconds(GameTestHelper helper) {
		ServerPlayer player = player(helper);
		NoelleEntity noelle = playedNoelle(helper, player);
		noelle.playerAttack();
		// Hit 1 lasts about 17 ticks; 2.5 s after it, the 4 s window still holds her place.
		helper.runAfterDelay(17 + 50, () -> {
			noelle.playerAttack();
			helper.assertTrue(noelle.comboNextForTests() == 2, "The next click is hit 2: " + noelle.comboNextForTests());
			helper.succeed();
		});
	}

	@GameTest(template = "empty", timeoutTicks = 100)
	public static void whenSheFallsStevePopsOutTwoSecondsLater(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		ServerPlayer player = player(helper);
		noelle.befriend(player);
		PlayAs.start(player, noelle);
		noelle.kill();
		helper.assertFalse(player.isPassenger(), "Out of her as she falls");
		helper.assertTrue(PlayAs.isFallen(player), "Steve held hidden for a moment");
		helper.runAfterDelay(PlayAs.FALL_DELAY - 5, () -> helper.assertTrue(PlayAs.isFallen(player), "Still hidden just before 2 s"));
		helper.runAfterDelay(PlayAs.FALL_DELAY + 5, () -> {
			helper.assertFalse(PlayAs.isFallen(player), "Steve's back after 2 s");
			helper.succeed();
		});
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
	public static void menuButtonsChangeHerSettings(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		ServerPlayer player = player(helper);
		noelle.befriend(player);
		CompanionMenu menu = new CompanionMenu(0, player.getInventory(), noelle);

		menu.clickMenuButton(player, CompanionMenu.BUTTON_MODE);
		helper.assertTrue(noelle.mode() == CompanionMode.WANDER, "Follow -> Wander");
		menu.clickMenuButton(player, CompanionMenu.BUTTON_MODE);
		helper.assertTrue(noelle.mode() == CompanionMode.STAY && noelle.isOrderedToSit(), "Wander -> Stay (sits)");
		menu.clickMenuButton(player, CompanionMenu.BUTTON_MODE);
		helper.assertTrue(noelle.mode() == CompanionMode.FOLLOW && !noelle.isOrderedToSit(), "Stay -> Follow");

		menu.clickMenuButton(player, CompanionMenu.BUTTON_BEHAVIOR);
		helper.assertTrue(noelle.behavior() == CompanionBehavior.WAIT_FOR_LEADER, "Aggressive -> Wait for leader");
		menu.clickMenuButton(player, CompanionMenu.BUTTON_BEHAVIOR);
		helper.assertTrue(noelle.behavior() == CompanionBehavior.PASSIVE, "-> Passive");

		helper.assertTrue(noelle.setting(CompanionEntity.SKILL_ON), "Skill starts on");
		menu.clickMenuButton(player, CompanionMenu.BUTTON_SKILL);
		helper.assertFalse(noelle.setting(CompanionEntity.SKILL_ON), "Skill off");
		menu.clickMenuButton(player, CompanionMenu.BUTTON_WEAPON);
		helper.assertFalse(noelle.setting(CompanionEntity.SHOW_WEAPON), "Weapon hidden");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void heldWeaponReplacesHerClaymore(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		noelle.befriend(player(helper));
		helper.assertTrue(noelle.getAttributeValue(Attributes.ATTACK_DAMAGE) == 3.5, "Claymore: 3.5");
		noelle.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		helper.succeedWhen(() -> helper.assertTrue(noelle.getAttributeValue(Attributes.ATTACK_DAMAGE) == 6.0,
				"An iron sword hits like a player's (6), not on top of her claymore: " + noelle.getAttributeValue(Attributes.ATTACK_DAMAGE)));
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
		helper.assertTrue(noelle.getAttributeValue(Attributes.ATTACK_DAMAGE) == attack + 0.5, "+0.25 attack a level");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void breastplateShieldsHer(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		// Right beside her: Breastplate reaches half as far as her claymore.
		Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 2));
		zombie.setNoAi(true);
		// A few ticks in: on a lagging test server, entities spawned this tick may not be findable yet.
		helper.runAfterDelay(5, () -> {
			noelle.useSkill(zombie);
			helper.assertTrue(noelle.shieldActive(), "The shield is up");
			helper.assertTrue(noelle.getAbsorptionAmount() > 0, "Absorption from Breastplate: " + noelle.getAbsorptionAmount());
			helper.assertTrue(zombie.getHealth() == zombie.getMaxHealth(), "No damage on the first frame: it lands with the animation");
			for (int i = 0; i < 6; i++) {
				noelle.tickAbilitiesForTests();
			}
			helper.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "The stone armor bursting out hurt the zombie next to her: health "
					+ zombie.getHealth() + "/" + zombie.getMaxHealth() + ", distance " + Math.sqrt(noelle.distanceToSqr(zombie)));
			helper.succeed();
		});
	}

	@GameTest(template = "empty")
	public static void sweepingTimeTurnsArmorIntoAttack(GameTestHelper helper) {
		NoelleEntity noelle = noelle(helper);
		Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 4));
		zombie.setNoAi(true);
		double before = noelle.getAttributeValue(Attributes.ATTACK_DAMAGE);
		noelle.useBurst(zombie);
		helper.assertTrue(noelle.burstActive() && noelle.sweeping(), "Sweeping Time is on (and synced)");
		helper.assertTrue(noelle.meleeReachScale() == 2.0, "Her reach doubles");
		helper.assertTrue(noelle.getAttributeValue(Attributes.ATTACK_DAMAGE) == before + 4.0, "Half her 8 armor as attack");
		helper.succeed();
	}

	@GameTest(template = "empty")
	public static void sweepingTimeSwingsInFrontOnTheBeat(GameTestHelper helper) {
		NoelleEntity noelle = helper.spawn(ModEntities.NOELLE.get(), new BlockPos(4, 2, 1));
		noelle.setNoAi(true);
		noelle.setYRot(0.0F);   // facing +z
		noelle.setYBodyRot(0.0F);
		Zombie front = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 6));   // 5 blocks ahead
		Zombie behind = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 0));
		front.setNoAi(true);
		behind.setNoAi(true);
		helper.runAfterDelay(5, () -> {
			noelle.setYBodyRot(0.0F);
			noelle.useBurst(front);
			helper.assertTrue(front.getHealth() == front.getMaxHealth(), "No damage at the cast: the swing lands with the animation");
			for (int i = 0; i < 20; i++) {
				noelle.tickAbilitiesForTests();
			}
			helper.assertTrue(front.getHealth() == front.getMaxHealth(), "Nothing on the first swing: it's only the wind-up");
			for (int i = 0; i < 16; i++) {
				noelle.tickAbilitiesForTests();
			}
			helper.assertTrue(front.getHealth() < front.getMaxHealth(), "The 180-degree swing reached 5 blocks ahead: health "
					+ front.getHealth() + ", distance " + Math.sqrt(noelle.distanceToSqr(front)) + ", facing " + noelle.yBodyRot);
			helper.assertTrue(behind.getHealth() == behind.getMaxHealth(), "Nothing behind her");
			helper.succeed();
		});
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
