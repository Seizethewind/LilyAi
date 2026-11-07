# taste-skill 04: deal the look
use when: step 04 of taste-skill, after the plan exists.

Open and read in full: `<skill>/references/layout-v4.md` and `<skill>/references/system.md`. For tech also open `backgrounds.md`, `effects-usage.md`, `moments.md` and (only if a scene is dealt) `scenes.md`. Palettes and pairings live in `<skill>/library/palettes.json` and `<skill>/library/type.json`.

## Rule
**Deal, never choose by taste.** Each command keeps a history, so pages differ. Take the palette and pairing that suit the client. Never violet: deal again.

## Commands (run only if `<skill>` and Node 22+ exist)
```bash
node <skill>/assets/layout/deal.mjs --type <page type> --has <photo,ui,object,film> --history .taste-skill/layout-history.json   # the hero; a game adds --kind gaming, hardware --kind tech
node <skill>/scripts/start.mjs deal --page <kind> --history .taste-skill/history.json    # three palettes, three pairings; add --axis after a word or editorial hero
node <skill>/scripts/start.mjs css --page <kind> --palette <id> --type <id> --radius soft --history .taste-skill/history.json
node <skill>/scripts/start.mjs brand --page place --bg "#.." --ink "#.." --accent "#.." [--surface "#.."] [--muted "#.."] --type <id> --radius soft   # a place: its own colours, in place of css
node -e "for(const l of process.argv.slice(1)){const o=l.split('|');console.log(o[Math.floor(Math.random()*o.length)])}" <the kind's lists in sections.md>
```

## Rules for the deal
- A product, hardware or a game with no photographs or art deals with `--has object`: always the `product` hero, its render's widest view across the wrap, no box. A game always passes `--has`.
- A place's hero is printed, never dealt (`--type place`).
- A dealt part that needs a photograph the page lacks is dealt again (`sections.md` section 2).
- A place uses `start.mjs brand` with the client's own colours, always (`sections.md` section 4, Look).
- Keep the `:root` block exactly as printed.
- Radius: `sharp` for fintech and developer tools, `soft` by default, `round` for consumer apps, square on a place.
- On tech only: the lit ground (`<html data-ground>`: `sand|sky|sage` through the one-liner), the CSS grounds (`backgrounds.md`), the one effect (`effects-usage.md`) and the moments (`moments.md`).
- Write every result on plan line 2 and in the comment at the top of `<body>`, and build exactly that. A short page drops what it has no section for (`sections.md` section 4).

## If you cannot run the scripts
Rotate by hand: pick a palette, font pairing and hero layout that differ from the last page you built. Say in the report that the deal was done by hand, not by the dealer.

## Done when
- Hero, palette, font pairing, radius (and on tech: ground, effect, moments, scene) are chosen and written on plan line 2.
- No violet or lavender anywhere in the design.
- A place has its own brand colours.
- Other kinds have no effect, scene or moment dealt.

Next: `taste-skill/05-build.md`
