# taste-skill 05: build
use when: step 05 of taste-skill, after the deal is written down.

Setup: copy `<skill>/assets` into the project's `assets` and start from `assets/starter.html`.

## Which asset serves which page
Open the reference of every asset family the page uses first. A dash means never.

| Need | Asset | Software, gaming | Hardware | Product, service | Place | Reference |
|---|---|---|---|---|---|---|
| Heights, edges, heroes, type | `system.css`, `assets/layout` | dealt hero | dealt hero | dealt hero | place hero | `layout-v4.md` |
| Sections and recipes | `starter.html` | its plan | product plan | its plan | place plan | `sections.md`, `sections-library.md` |
| Tokens, grid, buttons, nav, footer, colour | `system.css`, `system.js` | yes | yes | yes | place tokens | `system.md` |
| Copy, contacts, credits | | yes | yes | yes | yes | `copy.md` |
| Hover, press, focus, nav, tabs | `assets/motion` | yes | yes | yes | yes | `motion.md` |
| Card loops, a count | `assets/motion`, `assets/ui` | yes | yes | - | - | `motion.md`, `cards.md` |
| Photographs | `scripts/photos.mjs` | people, customers; a game's art | lead | lead | lead, 12-20 | `photos.md` |
| Real tool, app and file icons | `scripts/icons.mjs` | yes | yes | tools really used | - | `icons.md` |
| Feature cards, UI fragments | `assets/ui` | lead (gaming: none) | its app | an app the client runs | - | `cards.md` |
| Diagram cards (hub, lanes, pulses) | `assets/diagrams` | 0-2, pulsing | 0-2, pulsing | 0-1, still | - | `diagrams.md` |
| CSS grounds | `assets/backgrounds` | 0-2 dealt | 0-2 dealt | flat tokens | flat tokens | `backgrounds.md` |
| Shader grounds | `assets/effects` | the one effect | the one effect | - | - | `effects-usage.md` |
| Creative parts (`fx-*`) | `assets/components` | yes | yes | `fx-photo` plain, `fx-map` still | `fx-map` still | `effects-creative.md` |
| Objects and prints | `assets/objects` | one, as the effect; a game without art: its render | its render without photographs | its render without photographs | - | `effects-objects.md` |
| Award-site moments | `assets/moments` | 0-2 | 0-2 | - | - | `moments.md` |
| Big scenes | `assets/scenes` | 0-1 | 0-1 | - | - | `scenes.md` |
| Speed and weight | `scripts/perf.mjs` | yes | yes | yes | yes | `perf.md` |

## Steps
1. In `starter.html`: set `<html data-type data-radius data-buttons data-motion>`, the three metas (step 01) and the nav class.
2. Copy only the `<section class="hero ...">` of `assets/layout/hero-<id>.html`. Never copy its nav, other sections, footer or credit line. Every word and picture becomes the client's.
3. Move the dealt close and footer out of their `<template>`s and delete the rest.
4. Every section is `main > section` with one `.wrap`, its h2 first, `--h: 1` or `2`.
5. Build each section to its recipe with the system's classes and tokens. Write page CSS only where a reference says page CSS (inside a visual, `--p-h`, `.wall`, `.rail`, `.device`).
6. Paste UI from `assets/ui/<n>/fragment.html` and rewrite every name, number and logo to the client's. Kit icons become `data-icon` (`cards.md`).
7. A diagram card is `data-diagram="<preset>"` with the client's labels.
8. Run `node <skill>/scripts/icons.mjs scan index.html --out assets/icons --colour` last, so every icon the page names is a local file (if the script exists).
9. Mount an effect, object, moment or scene exactly as its reference says, never from your own script.
10. Credits: the one footer line from `photos.mjs credits`, once the markup uses the photographs (it reads the page).
11. Motion: the motion layer does hover and press (house rule 5). Never strip or override it.

## Done when
- The page is built from `starter.html` with the dealt hero and close.
- Every section is `main > section`, one `.wrap`, h2 first.
- Every icon is a local real icon; no drawn look-alike logos.
- Credits are one footer line; no caption under any image.
- Other kinds have zero effects, scenes, moments, loops or gradients.

Next: `taste-skill/06-check.md`
