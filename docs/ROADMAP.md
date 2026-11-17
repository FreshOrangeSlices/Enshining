# Enshining roadmap (decided notes)

Nothing here is built yet unless marked done. Order is roughly the order we'll do it in.

## Now: Noelle's rebuild (your side, in Blockbench)
- Done: she's on the shared slim-arm girl rig (`docs/art/noelle/noelle_rig.bbmodel`, all her animations inside) with
  her new skin, the A-line skirt, and her old pieces carried over as placeholders (headdress, roses, neck rose,
  chest plate, pauldron, belt, bow with `flow_` tails), the claymore in hand and a copy hovering behind (`weapon_back`).
- The skirt's texture: Blockbench's GeckoLib format shows only one texture per model, so the skirt gets its own
  area on her main texture (`tools/merge_skirt_texture.py` folds it in). The game also still accepts a separate
  `NAME_skirt.png`.
- Skirt motion (done, code): any cubes under a `skirt` group move as one connected ring. Each panel hinges where it
  meets the waist (the highest panels), lower tiers and frill hang from the same spot. The front swings with the
  forward leg and the back with the back leg (up to 20 degrees), fading out toward the sides. Springs give it
  weight: the hem trails behind her when she moves and swings past when she stops, flares out (up to 12 degrees)
  when she spins or falls and bounces on landing, and lags behind turns and spins (lower panels more, up to 15
  degrees). All of it tested gap-free on the 2-px panels with 25% overlap. Keyframing just `skirt_front` /
  `skirt_back` still moves them as solid blocks (seams can show): keep those keys small, or key `skirt`.
- Eyes: "eyes_open" / "eyes_half" / "eyes_closed" / "eyes_happy" are whole-face sheets 0.1 px in front of her
  face (like Ganyu's in Migamigos), one shown at a time, each with its own 8x8 patch (started as copies of her
  face; `tools/add_eye_overlays.py`). Code: blinks every 3-7 s; happy for 3 s on level up, a gift, opening her
  menu, winning a fight or you coming back after 10 s away (closed eyes if there's no happy drawing). Sitting:
  0-30 s drifting (dozing more and more, flicking awake), 30-90 s dozing (half, nodding off), then asleep
  (closed). Shut while hurt and in the menu's armor pose.
- Menu poses done: `menu_weapon` (draws the claymore) and `menu_armor`. Each plays its start once, then repeats
  the rest (`tools/pose_loops.py`, easing back to the loop's start): armor loops from 0.42 s, weapon from 0.96 s.
  Zooming back out is slower (in fast, out slow) and she relaxes back to idle over 0.8 s.
- Weapons appear and vanish by magic (code): the claymore grows in over 0.15 s and shrinks away over 0.2 s, the
  hovering one floats down into place, with specks of her element's colour and little falling sparks scattered
  over the blade's own cubes while it does (in the world; not in the menu yet).
- Painting map: `docs/art/noelle/uv_guide.png` (labelled) and `uv_template.png` (guide layer), made by
  `tools/make_uv_guide.py`. The chest plate (bust) has its own pixels on every face now.
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
