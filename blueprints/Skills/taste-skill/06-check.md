# taste-skill 06: check and look
use when: step 06 of taste-skill, after the page is built.

Open `<skill>/references/perf.md` and `<skill>/references/layout-v4.md` section 6 (the check).

## How to check
Only the skill's scripts, each running one headless Chrome with WebGL on the CPU, closed by its own PID, one at a time. Never a windowed browser or a browser tool. Each serves the page file itself over http on 127.0.0.1, never `file://`, so no server is needed. For a built page whose links start at the site root (`/_astro/page.css`), pass `--root` with the build folder (`dist`).

## Commands (run only if `<skill>` , Node 22+ and Chrome/Edge/Chromium exist)
```bash
node <skill>/scripts/consistency.mjs index.html --widths 1440,1024,820,768,390 --rhythm-widths 1440,820,768,390 --profile <dir> --page <kind> --category "<what is sold>" --primary "<main action>"
node <skill>/assets/layout/check.mjs --url index.html --profile <dir> --sizes 1440x900,1024x768,820x1180,768x1024,390x844 --shots shots/layout
node <skill>/scripts/shoot.mjs --url index.html --w 1440 --out shots/1440.png --profile <dir>
node <skill>/scripts/perf.mjs index.html --profile <dir>
```
Shoot 1024, 820x1180, 768x1024 and 390 as well. Every `check.mjs` finding fails the page (no `--no-stress`). Fix every FAIL (one you judge false goes in the report with its reason), shoot again, and read every screenshot against the checklist.

## Checklist
Each line names its checks: `consistency.mjs` ids in brackets, `check.mjs` findings in braces, `perf.mjs` rows. The last line is for your eyes.

- **First screen:** says what is sold, for whom or where, with one hard fact [copy-headline, copy-fact]; the hero fills it alone at every width, its main button inside it [hero-screen, hero-fill, fold-action, fold-cut] {hero-fold}.
- **One section per screen:** every section 1 or 2 screens, its h2 first and top-aligned, no neighbour's heading or picture in its tile, nothing sparse [section-height, one-section, one-heading, section-air, one-screen, section-gaps] {screen-short, screen-height, sparse, neighbour, neighbour-ground}.
- **Equal edges:** every block on the `.wrap` lines or the grid's columns, nothing out to one side, no sideways scroll [container-edges, row-lines, split-pair, off-grid, right-edge, col-edge, spill, off-viewport, stagger, top-stagger] {edge-left, edge-right, spill, bleed-one-side, one-sided, lines}.
- **Every visual draws** at 1440, 1024, 768 and 390; a refused effect shows its still fallback [visual-empty, empty-visual, gl-fallback, shader-lint, no-visual, photo-weight].
- **No caption** explains, apologises for or labels a picture; credits in one footer line [visual-caption, caption, credit-wall, photo-credit]; no placeholder, sample note or printed `.example` address [placeholder, sample-label].
- **The right things for the kind:** no effect ground, print, scene or moment outside tech (WebGL only for a product render) [effect-kind, effects-fit, photo-print]; the subject itself shows: the product big, whole in three sections at most, a new picture each [product-shown, same-object, photo-repeat]; each asset the brief names in its slot; a game's name and its world as key art, the diff of a code-review tool, software's `app-frame`; real photographs where they fit, none twice [photo-set, photo-repeat, photo-upscale]; real icons in their own colours; flat grounds on other [grounds]; a place deals two different compositions and one type moment.
- **Motion:** every button, link, linked card and linked photograph visibly answers the pointer, nothing tilts [motion-layer, hover, hover-move, tilt]; card loops cycle in 2.5-4 s [loop-cycle]; nothing moves because the page scrolled except a dealt moment or the big scene inside its own section on tech [scroll-linked, scroll-reveal, pinned: expected only there]; anything moving past 5 s has a pause button [loop-pause]; under reduced motion everything rests and nothing stays hidden [reduced-motion, hidden-content]; at most four live contexts [webgl].
- **Contacts** as house rule 9 [street-number, postcode, coordinates, company-number, phone-number].
- **One system:** one button pair, one card style, one radius scale, one link look [button-styles, button-shape, card-family, card-layout, link-styles]; two boxes deep at most [nesting]; one heading scale, its faces loaded [heading-scale, font-load]; text contrast [text-contrast, contrast].
- **Copy and links:** headings name things, buttons a verb and an object, at most two hero actions [copy-headings, copy-buttons, copy-actions, action-count]; one fact, one wording [fact-agree]; every link lands, forms send [links, forms].
- **Fast:** no FAIL row in `perf.mjs` (image weight, contexts, layout shift).
- **820, 768 and 390:** sections on their screens [section-height], the first screen ending whole or on a clear bleed [fold-card] (`layout-v4.md`); splits stack text first, no row half empty; one column at 390, buttons full width, a full-screen menu; the place action bar.
- **Your eyes:** beside the figma.com and x.ai frames it reads as a professional company site; nothing tilted, staggered, cut or overlapping; the hero is the work, not a mood.

## If you cannot run the scripts
Check each line above by reading the page and its CSS, and say in the report which lines you could not verify in a browser. Report each as pass or fail. Never claim a pass you did not see.

## Done when
- Every checklist line is reported pass or fail, or marked not checked.
- Every FAIL is fixed, or listed with the reason you think it is false.
- Screenshots were read against the checklist (if you could take them).

Next: `taste-skill/07-report.md`
