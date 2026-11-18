Python copies of the game's skirt code (`CompanionRenderer.SkirtRing`), for checking a model before it goes in.

- `check_sway.py model.bbmodel`: the deepest any part of `waist_extras` goes under the skirt, over every extreme
  of the skirt's swing, flare, twist and whole-skirt sway, with the ride-on-the-skirt code. Negative = on top.
- `check_field.py model.bbmodel [--rest] [--static]`: the same per piece (without the sway); `--static` = as if
  nothing rode the skirt.
- `nudge.py in.bbmodel out.bbmodel [clearance] [--along=GROUP]`: moves waist pieces that sit inside the skirt at
  rest straight out until they clear it (pieces of GROUP move together, toward its pivot).
