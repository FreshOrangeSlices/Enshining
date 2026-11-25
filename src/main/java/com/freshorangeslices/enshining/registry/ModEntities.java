package com.freshorangeslices.enshining.registry;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.character.noelle.NoelleEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Every companion's entity type. A new character adds one line here, its attributes and its spawn rule. */
public final class ModEntities {
	public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Enshining.MODID);

	public static final RegistryObject<EntityType<NoelleEntity>> NOELLE = ENTITIES.register("noelle",
			() -> EntityType.Builder.of(NoelleEntity::new, MobCategory.CREATURE).sized(0.6F, 1.8F).clientTrackingRange(10).build("noelle"));

	private ModEntities() {
	}

	public static void attributes(EntityAttributeCreationEvent event) {
		event.put(NOELLE.get(), NoelleEntity.createAttributes().build());
	}

	public static void spawnPlacements(SpawnPlacementRegisterEvent event) {
		event.register(NOELLE.get(), SpawnPlacements.Type.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				Animal::checkAnimalSpawnRules, SpawnPlacementRegisterEvent.Operation.REPLACE);
	}
}
