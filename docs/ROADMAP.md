# Enshining roadmap (decided notes)

Nothing here is built yet unless marked done. Order is roughly the order we'll do it in.

## Now: Noelle's rebuild (your side, in Blockbench)
- Move to the shared slim-arm base rig (`docs/art/base_rigs`), redo her skin to match.
- New skirt (`skirt` group, `skirt_front` / `skirt_back` follow her legs).
- `flow_` bones for bangs and ribbon; eyes (`eyes_open` / `eyes_half` / `eyes_closed`); `weapon_back`.
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
