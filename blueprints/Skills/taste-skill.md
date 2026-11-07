# taste-skill
use when: the request is to build or redesign a company, product, shop or place website / landing page and it should not look like a generic AI page.

Source: Leonxlnx/taste-skill 2.5.0 (MIT), condensed. The full skill needs Node 22+ and Chrome; this blueprint keeps only the design rules, so it works without them.

## Stack
- Plain HTML + CSS first (one `index.html`, one stylesheet). Port to React/Vue/Svelte/Tailwind only if the prompt asks.
- Google Fonts: one pairing. Real photographs and real brand icons, never emoji or stock-looking placeholders.
- Effects (shaders, scroll scenes, 3D) only for `software`, `tech`, `gaming` pages. Never for cafes, hotels, shops, studios, services.

## Steps
1. Classify the business: `software`, `gaming`, `tech`, `product`, `service` or `place`. Say which in one line.
2. Plan the page screen by screen before writing markup. One section per screen at 1440x900.
3. Pick one design system: one palette, one font pairing, one set of spacing tokens as `:root` variables. Reuse them everywhere.
4. Pick one hero layout: split, centred, product, photo, place, collage, editorial, word, film, statement or triptych.
5. Layout rules: equal left and right content edges, fixed nav bar, single-screen closing footer.
6. Copy rules: plain copy, no filler, no invented street numbers, no lorem ipsum or placeholder text.
7. Visuals by kind:
   - software/tech: real product UI fragments in feature cards, node-and-line diagrams, real brand icons.
   - place/shop/service/product: curated photographs, sharp type, flat colour. No captions under images; credit sources in one expandable footer line. Mark library photos as draft stand-ins if the client has none.
8. Micro-interactions stay quiet (hover, focus, small fades). Respect `prefers-reduced-motion` and give phones a static fallback.
9. Check at 1440, 1024, 768 and 390 wide: no empty visuals, no contrast drops, no misaligned columns, no section-height drift, no overflow.

## Example
Brief: "website for a neighbourhood coffee roaster". Kind: `place`. Hero: photo or editorial. Flat warm palette, one serif + one sans, roast shown in photographs, FAQ as simple stacked cards, zero WebGL, credits in one footer line.

## Done when
- The kind is named and no effects appear on a non-tech/gaming/software page.
- One palette and one font pairing are used across the whole page.
- Every screen is one section; left and right edges line up.
- No placeholder text, no lorem ipsum, no emoji used as icons.
- Looks right at 1440, 1024, 768 and 390 wide (checked in the sandbox if available, otherwise say it was not checked).
