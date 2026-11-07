# taste-skill 03: plan the page as screens
use when: step 03 of taste-skill, after the copy is written.

Open and read in full: `<skill>/references/sections.md` (plan in screens, the deal, the plans, place, visuals) and `<skill>/references/layout-v4.md` section 2 (one section per screen, heights). Also open `<skill>/references/photos.md` to pick photographs.

## Page plan by kind
- **Place** (cafe, bakery, guesthouse, shop). Photographs lead: 12 to 20 on the full page, 8 to 12 compact, under 8 frames the 3-screen short page, whatever the trade. No client photographs: use the draft path in `photos.md`, then count. The hero is a photograph whenever a frame fits (a remote place: its region's landscape). Visit block on screen two, then the offer, the place, orders or booking. Variety without effects (`sections.md` section 4): the offer and the place deal different compositions, never two sections round one picture; one type moment (address or name set large); one main action a screen; list text 16px. Flat grounds, square photographs, one button style, the phone bar. No effect, ground, moment, loop, fragment, diagram or tab.
- **Product and service** (site-kind other). Photographs lead: the product, the people, the rooms, the work; two or more a screen on a product page. Real icons for the tools a studio uses; one still diagram at most; flat grounds and one brand band. No shader effect, print, grain, gradient, moment, scene or loop; WebGL only for the product render. A physical product with no photographs is shown by its render (`assets/objects/product-render`, stills) leading the hero, then the full product page, never a drawing or mood stock.
- **Every product page** (photographed or rendered, hardware too; the bar is Figure and 1X): the product **big** where it stands whole (most of its frame, or on the ground at anchor scale), never mid-size in a grey rounded panel on a grey ground; **a different picture each section**, never one view twice; **every headline promise pictured** (a family bike shows the child bench); dealt compositions, no structure twice, one contrasting ground; the **swatch changes the picture**, every colour shown once; **no fact twice**; buttons step and press (house rule 5).
- **Tech** (software, gaming, hardware). The client's own subject leads at true size. Software: its own UI as live HTML showing what it works on (a code-review tool: a red and green diff with its Accept action) in one `app-frame`, never a generic dashboard or only fragments on panels; cards are fragments on lit panels, pulsing diagrams, real icons. Hardware: its photographs, else its render, never a split hero of only an order form or a drawn SVG. A game: its name in the h1, a launch line, its world as full-bleed key art (else its render), an effect never in its place (`sections.md`, Gaming). By deal: **one effect** (a shader ground in the hero or the one `[data-visual]` frame, a WebGL part, an object or one big scene) and **one or two moments** in their own sections; four live WebGL contexts at most; no card moves with the scroll (`effects-usage.md`).
- **One object never carries the page.** The product or hero object whole leads the hero and two more sections at most. Another kind of visual the brief supports carries the rest (`sections.md` section 1, its table): a game's world, crew and map; hardware's use at the bench, an exploded or parts view; a still diagram card, a type moment, a context photograph where the trade allows.
- **The client's own assets get slots.** A trailer, key art, screenshots, CAD renders or a motion video the brief names each get a frame that reads finished now (a real `<video>` with a kit poster, a play control only when there is a page or file to play, three to five world captures from the kit, a CAD frame), named in a comment for the client's file, never empty (`sections-library.md`, client-slots).

## Steps
1. Write the plan. Line 1: the kind. Line 2: the deal (filled in step 04). Then one line per section: its id, its one idea, its picture and its kind (object, use, parts, world, slot, diagram, type; three object lines at most), its screens (1 or 2, from its content height, `layout-v4.md` section 2). On tech, say which section holds the effect, the scene or a moment.
2. Pick the photographs now (`photos.md`). Their count, stand-ins included, sets a place's length.
3. Open `<skill>/references/cards.md`, then the recipe of every section id you use in `<skill>/references/sections-library.md`. Read only the recipes you use.

## Done when
- The plan has the kind on line 1 and one line per section.
- Every section is 1 or 2 screens and the hero is alone on screen one.
- No object leads more than the hero and two more sections.
- Photographs are picked and counted (place: within the range above).
- Every client-named asset has a slot.

Next: `taste-skill/04-deal.md`
