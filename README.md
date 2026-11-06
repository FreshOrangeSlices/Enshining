# Enshining

Elemental companions for Minecraft Forge 1.20.1. Befriend them, and they follow you, fight beside you, level up,
and use an elemental skill and burst. Fan-made characters inspired by Genshin Impact (not affiliated with HoYoverse).

Needs [GeckoLib](https://modrinth.com/mod/geckolib) 4.4+.

## Companions

| | Element | Befriend with | Skill | Burst |
|---|---|---|---|---|
| Noelle | Geo | Bread | **Breastplate**: a Geo shield on her and you; her hits may heal you both | **Sweeping Time**: 15 s, armor becomes attack and her swings hit everything around the target |

Planned next: Childe (Hydro) and Lumine (Dendro + Hydro to start).

## Playing

- **Befriend:** right-click a wild companion with its gift (a 1 in 3 chance each time). Noelle spawns in forests.
- **Modes:** right-click your companion with an empty hand: Follow → Wander (roams near that spot) → Stay.
- **Status:** sneak + right-click with an empty hand: level, XP and burst energy.
- **Heal:** right-click with its gift.
- **Levels:** companions earn XP from kills (up to level 40); each level adds 2 health and 0.5 attack.
- **Fights:** a companion uses its skill when it's off cooldown and its burst when its energy is full (hits charge it).
  Your own swings never hurt it, and it never attacks creepers.

## Making a character (art)

Every companion uses the same rig, `docs/art/companion_rig.geo.json`: a slim-arm player model (with the outer layer)
with the torso split in two (`body` bends at the hips, `chest` at the waist; head and arms ride on `chest`)
elbows (`right_forearm`, `left_forearm`), knees (`right_shin`, `left_shin`)
and a `weapon` bone in the right hand (on the forearm). That means:

- **Textures are Minecraft skins.** Any 64x64 slim-arm skin fits; paint it in any skin editor. The texture is 128x64:
  the skin on the left half, the weapon's pixels on the right.
- **Shared animations:** idle, walk, sit and hurt are the same for everyone (`docs/art/companion_base.animation.json`).
  A new character's animation file is that one plus its own attack, skill and burst.
- **Tools:** [Blockbench](https://www.blockbench.net) with the GeckoLib plugin opens the model and animations.

Files per character: `assets/enshining/geo/entity/NAME.geo.json`, `textures/entity/NAME.png`,
`animations/entity/NAME.animation.json`.

## Trying it

The [latest release](https://github.com/FreshOrangeSlices/Enshining/releases/tag/latest) always has the newest passing build:

- **Enshining-Test.mrpack**: open it with the Modrinth App (or add an instance from file). It sets up Forge 1.20.1, GeckoLib and Enshining in one go.
- **Enshining.jar**: just the mod, for any Forge 1.20.1 setup with GeckoLib.

## Building

CI (GitHub Actions) runs `./gradlew build runGameTestServer`; the jar to install is the artifact `enshining-jar`.

## License

CC0 1.0 (public domain) for this mod's code and its own art. The characters it depicts belong to HoYoverse.
