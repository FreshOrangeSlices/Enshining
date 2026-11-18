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
- Worn over the skirt (done, code): everything in `waist_extras` (belt, bows, shield) rides the skirt under it.
  Each corner follows the skirt's swing, flare and sway at its spot around the waist (from half a pixel above the
  waist line down), and lifts off a hair while the skirt moves, so the skirt never comes through. Checked against every
  extreme of the skirt's motion: all of Noelle's waist pieces stay at least 0.12 px on top (before: the back bow's
  tails went 2.2 px under). Keep `waist_extras` beside `skirt` (both straight under `body`), and model pieces on or
  just outside the skirt at rest: the belt rides, it doesn't push out of the skirt by itself.
- Belt bow: its own spot at 3x detail (v. 230-242 on the texture, `tools/reuv_group.py`), started as a copy of what
  it showed. The back bow's wings moved 0.7 px back and a few chain pieces 0.1-0.4 px out: they sat inside the skirt.
- Eyes: "eyes_open" / "eyes_half" / "eyes_closed" / "eyes_happy" are whole-face sheets 0.1 px in front of her
  face (like Ganyu's in Migamigos), one shown at a time, each with its own 8x8 patch (started as copies of her
  face; `tools/add_eye_overlays.py`). Code: blinks every 3-7 s; happy for 3 s on level up, a gift, opening her
  menu, winning a fight or you coming back after 10 s away (closed eyes if there's no happy drawing). Sitting:
  0-30 s drifting (dozing more and more, flicking awake), 30-90 s dozing (half, nodding off), then asleep
  (closed). Shut while hurt and in the menu's armor pose.
- Menu poses done: `menu_weapon` (draws the claymore) and `menu_armor`. Each plays its start once, then repeats
  the rest (`tools/pose_loops.py`, easing back to the loop's start): armor loops from 0.42 s, weapon from 0.96 s.
  Zooming back out is slower (in fast, out slow) and she relaxes back to idle over 0.8 s. A close-up stays 4 s
  after you stop hovering its slot, 6.5 s after you equip something; posing, she stops following the cursor.
- Weapons appear and vanish by magic (code): the claymore is in her hand at once (a grow-in read as a delay at the
  start of a swing) with a quarter-second of sparkle over it, and shrinks away over 0.2 s; the hovering one floats
  down into place. Specks of her element's colour and little falling sparks scattered over the blade's own cubes
  (in the world; not in the menu yet).
- Skill and burst (code): one at a time, each plays to its end (before, the next tick could start the other on top:
  the burst got skipped, the skill cut off). Skill: she stands still for the whole animation; stone bursts out all
  round her at half her claymore's reach, 0.25 s in. Burst: she can walk at half speed; 0.9 s in her claymore
  sweeps 180 degrees in front of her (right to left over 0.2 s), 6 blocks out.
- Combo: her last, downward hit does 1 more damage and only catches what's in a 50-degree slice ahead (a third of
  the others' 150).
- Charged attack (code ready, off): `animation.noelle.charged`, every second combo in place of starting over when
  an enemy is still in reach after the last hit, hitting all round her. Switched on (with its length and hit
  moments) once it's animated.
- Lighting in the world: flat sheets (eyes, straps, panels) are lit as the side you see, like any face (GeckoLib's
  own rule made her eyes glow against her face whenever you looked down at her); and her faces are lit softly
  (normals tipped halfway to the sky): front faces ~0.92 bright instead of ~0.74, sides ~0.83 instead of ~0.59.
  The menu portrait keeps its own lighting.
- `flow_` bones: sway on top of the rest pose (or the animation's key), never on top of last frame's sway (that
  added up into the bows spinning). Blinks: 4 ticks while moving or fighting, the slow 7 when standing still.
- v8 model: skirt and everything in `waist_extras` 2 px higher, hovering claymore 2 px higher, the mini shield's
  own 5x7 faces (rows 230-236 at the right edge; a plain grey placeholder, it had no paint).
- Painting map: `docs/art/noelle/uv_guide.png` (labelled) and `uv_template.png` (guide layer), made by
  `tools/make_uv_guide.py`. The chest plate (bust) has its own pixels on every face now.
- Extra idles (code done): animations named `animation.companion.idle_<anything>` (or `animation.noelle.idle_...`)
  play once now and then (every 8-18 s standing idle), a different one each time.
- Glow (code done): bones named `glow_...` (and what's under them) render at full brightness, e.g. her Vision.
- Armor visuals: cancelled (modded armor can't be matched); armor slots still give stats. The rig's armor_*
  bones stay hidden; the menu's armor (eye) button is gone.
- Starters to reshape (v5): burst glow in `weapon_aura` (`tools/add_burst_aura.py`: the claymore at 1.3x, soft
  gold see-through) and a diagonal chain belt in `chain_belt` (`tools/make_chain_belt.py`: strap round the back,
  chain across the front, joint rings); the old `belt` is switched off from export.
- Mark 1 = this model, finished as is. Mark 2 later: a more detailed "voxel character" style (see the reference
  the user sent: chunky layered clothes, big sleeves/hat, hair in locks, double-detail shading).
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
