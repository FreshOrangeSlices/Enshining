package com.freshorangeslices.enshining.character.noelle;

import java.util.List;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.core.animation.RawAnimation;

/**
 * Test Noelle (spawn egg only): the same character, but her combo plays as ONE animation start to finish, the way
 * Migamigos does it, with damage at each hit's impact. For comparing against the cut-into-hits version.
 */
public class NoelleFullComboEntity extends NoelleEntity {
	/** The four hits and the ending joined back together (5.96s); impacts at the same moments as the cut hits. */
	private static final List<ComboHit> FULL_COMBO = List.of(
			new ComboHit("animation.noelle.full_combo", 5.9583, new double[] {0.4167, 1.375, 2.75, 3.5417}, 5.9583));

	public NoelleFullComboEntity(EntityType<? extends TamableAnimal> type, Level level) {
		super(type, level);
	}

	@Override
	protected List<ComboHit> comboHits() {
		return FULL_COMBO;
	}

	@Override
	protected RawAnimation comboEndAnimation() {
		return null; // the ending is already part of the one animation
	}
}
