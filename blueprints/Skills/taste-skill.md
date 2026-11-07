# taste-skill
use when: the request is to build, redesign, restyle or review a company, product, shop, cafe, hotel, studio, SaaS, AI, developer-tool or game website / landing page, or the user says a page looks generic, templated or "AI-made".

Source: Leonxlnx/taste-skill 2.5.0 (`design-taste-frontend`, MIT), condensed from its SKILL.md. The full skill also ships Node scripts (palette dealer, browser checker) and ~1800 asset files; this blueprint keeps only the rules and plan, so it works without them.

## Stack
- Any stack the prompt names (HTML/CSS, React, Next.js, Vue, Svelte, Astro, Tailwind). Default: one `index.html` plus one stylesheet.
- One system: tokens as `:root` variables (palette, one Google Fonts pairing, one radius scale), one button pair, one card style.
- Real photographs and real full-colour product icons. Never a drawn look-alike of a real logo. Never violet or lavender in the page's own design.

## Steps
1. **Classify the kind** and write it on the first line of the plan:
   - `software`: app, SaaS, AI, developer tool, fintech app.
   - `gaming`: a game or a studio launching its game.
   - `tech`: hardware or a tech device.
   - `product`: any other physical product or brand.
   - `service`: people are hired (clinic, law firm, agency, trade).
   - `place`: people come to it (cafe, bakery, restaurant, small hotel, shop, gym). A maker that also opens its door or teaches is a `place`.
   "Tech" = software, gaming, tech. Everything else is "other".
2. **Write the copy first.** The h1 names the category and the buyer or the place (a game: its name and genre). One hard fact on the first screen. A noun in every heading. Buttons are a verb and an object. At most two hero actions, one main action per screen. Invent nothing except a coined brand name and, on a place, plausible hours and prices. No invented reviews, quotes, customers or figures. On tech, 60 to 80 words per screen.
3. **Plan the page as screens**, one line each: id, one idea, its picture, 1 or 2 screens tall. Hero alone on the first screen. Pick the photographs now.
4. **Choose the look by rotation, not taste:** vary palette, pairing and hero layout from past pages. Hero layouts: split, centred, product, photo, place, collage, editorial, word, film, statement, triptych. Radius: sharp for fintech and developer tools, soft by default, round for consumer apps, square on a place.
5. **Build by kind:**
   - `place`: photographs lead (12 to 20 on a full page, 8 to 12 compact). Photo hero, visit block on screen two. Flat grounds, square photographs, one type moment (address or name set large). No effects, no gradients, no loops.
   - `product` / `service`: photographs of the product, people, rooms, work lead. Product shown big and whole, a different picture each section, every headline promise pictured. No photographs: use a product render. Flat grounds, no shaders or scroll effects.
   - `software` / `tech` / `gaming`: the client's own subject leads at true size. Software shows its own UI as live HTML in one app frame, never a generic dashboard. A game shows its name in the h1 and its world as key art. One effect at most (shader ground, object or big scene) plus one or two creative moments in their own sections. At most four live WebGL contexts.
   - One object never carries the page: it leads the hero and two more sections at most.
6. **Layout:** every section is one `.wrap` with its h2 first, exactly 1 or 2 screens tall at 1440x900. Equal left and right content edges. Fixed nav, single-screen closing footer. At 390 wide: one column, full-width buttons, full-screen menu.
7. **Motion:** linked cards lift 2px, photos zoom 1.02 to 1.03 inside their frame, buttons step on hover and press on click, underlines draw. Never tilt, carousels, timed tabs, scroll reveals, fade-ins or parallax. Anything moving past 5 s gets a pause button. Under `prefers-reduced-motion` everything rests and nothing stays hidden.
8. **Contacts and credits:** street and district only, no house number, postcode, coordinates, register or VAT number, or real-format phone; use `.example` contacts. No caption under any image. Photo credits in one footer line.
9. **Report:** the kind, the look chosen, what was checked and what was not, every assumed price, hour or address, and the photographs or art the client must supply.

## Example
Brief: "site for a neighbourhood coffee roaster with a small cafe". Kind: `place`. Plan: photo hero, visit block, the roast in four photographs, menu, order/directions, FAQ as stacked cards. Flat warm palette, one serif plus one sans, square photographs, zero effects, credits in one footer line, main action "Get directions".

## Done when
- The kind is named on the plan's first line.
- The first screen says what is sold and for whom or where, with one hard fact and its main button inside it.
- Every section is 1 or 2 screens, h2 first, with equal left and right edges and no sideways scroll.
- No effect, shader, scene or scroll animation on a `place`, `product` or `service` page.
- No caption under images, no placeholder or lorem text, no emoji used as icons, no violet.
- One palette, one font pairing, one button style across the page.
- Every visual draws at 1440, 1024, 768 and 390 wide (checked in the sandbox if available; otherwise say it was not checked).
