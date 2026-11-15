# Enshining roadmap (decided notes)

Nothing here is built yet unless marked done. Order is roughly the order we'll do it in.

## Now: Noelle's rebuild (your side, in Blockbench)
- Done: she's on the shared slim-arm girl rig (`docs/art/noelle/noelle_rig.bbmodel`, all her animations inside) with
  her new skin, the A-line skirt, and her old pieces carried over as placeholders (headdress, roses, neck rose,
  chest plate, pauldron, belt, bow with `flow_` tails), the claymore in hand and a copy hovering behind (`weapon_back`).
- The skirt has its own texture (`noelle_skirt.png`, the project's second texture): paint it there.
- Skirt motion (done, code): any cubes under a `skirt` group move as one connected ring. Each panel hinges where it
  meets the waist (the highest panels), lower tiers and frill hang from the same spot. The front swings with the
  forward leg and the back with the back leg (up to 20 degrees), fading out toward the sides; the whole ring
  flares out up to 12 degrees when she spins (animation spins or turning). Keyframing just `skirt_front` /
  `skirt_back` still moves them as solid blocks (seams can show): keep those keys small, or key `skirt`.
- Still yours: paint the skirt; redo belt + shield, scarf (`chest_extras`, `flow_` tails), shoulder piece;
  `flow_` bangs; eyes (`eyes_open` / `eyes_half` / `eyes_closed`); move `weapon_back_model` where you like it.
- Animations: skill, jog, idle fidgets; later `weapon_aura` for the burst.

## Companion menu (right-click her)
- Equipment: 4 armor slots + 1 weapon slot (no other storage).
- Visual toggles: armor shown/hidden, weapon shown/hidden.
- Skill on/off, burst on/off.
- Behavior: Aggressive (any hostile) / Wait for leader (what you hit, or what hits you or her) / Passive.
- Follow / Wander / Stay, level, health, energy.

## Menu portrait (layout built; poses are yours)
- Big animated portrait of her (a copy that only exists in the menu), full body by default.
- Hovering or changing the weapon slot: camera eases in toward her hands, she plays `animation.companion.menu_weapon`
  holding her weapon. Hovering or changing an armor slot: eases in on her upper body, `animation.companion.menu_armor`
  (the praying-hands pose). Otherwise `animation.companion.menu_idle`. Missing ones fall back to her normal idle.
- Bottom bar: eye (armor shown), sword (weapon shown), colored dot (green passive / yellow wait for leader /
  red aggressive), arrow (follow / wander / stay); skill and burst toggles on the right.

## Armor and weapons
- Armor drawn in segments on our joints (each segment uses its slice of the armor texture), so it bends.
  Vanilla and most modded armor; dyes; trims later. Special 3D-model armor: still protects, auto-hidden.
- Wearing armor hides the outfit piece under it (helmet: head extras + hair; chestplate: chest extras;
  leggings: skirt + waist extras; boots: foot extras).
- Weapon swap: a held weapon REPLACES the claymore's damage (+ her level bonus); enchantments apply.
  Skill and burst work with any weapon; the burst keeps its magic overlay and doubled reach.

## Damage
- Base claymore damage 3.5 (1.5 combos kill a zombie). Level bonus +0.25 attack per level.

## Levels and ascension
- Level cap around 30-40, with ascension caps along the way.
- XP from damage she deals, plus some from damage you deal near her.
- Ascension costs lists of mob drops, Genshin-style (e.g. 60 rotten flesh, gunpowder, bones early;
  rarer and boss drops later).
- Skill and burst strength grow with her level (no separate talent books).

## Later (notes only)
- Death: she drops an item that re-summons her (like the modpack's pet system).
- HUD: a portrait per companion with a health bar.
- Piloting a companion (Steve idle and defenseless, bigger third-person camera).
- Childe (man rig: +1 head, +1 torso) and Lumine (girl rig); universal hovering back weapon.
