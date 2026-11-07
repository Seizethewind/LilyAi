# taste-skill 01: brief and kind
use when: step 01 of taste-skill, after `00-start.md`.

## Steps
1. **Read the brief.** Write down: the kind (table below), what is sold, who buys it, the one main action, and the real material (logo, screens, photographs, prices, address, hours).
   - The main action is the transaction: Book a room, Order a cake, Start a free trial. A place with neither: Get directions.
2. **Pick the kind from this table.** The kind decides everything after this.

| Brief | page type | site-kind | kind |
|---|---|---|---|
| App, SaaS, AI, developer tool, fintech or banking app, a studio selling its own software | software | software | software |
| A game, a studio launching its game | software | gaming | gaming |
| Hardware or a tech device (a drone, a robot arm, a keyboard) | product | tech | tech |
| Any other physical product or brand (a chair, a bike, coffee beans sold online) | product | other | product |
| People are hired: clinic, law firm, agency, creative studio, trade | service | other | service |
| People come to it: cafe, bar, bakery, restaurant, guesthouse, small hotel, shop, florist, bike shop, gym, local studio | place | other | place |

3. **Maker that also opens its door or teaches** (a ceramics studio, a bakery with a web shop) is a **place**; its web shop becomes the goods section. It is a product only when nothing happens at the door.
4. **"Tech" and "other".** "Tech" means site-kind software, gaming or tech. "Other" is everything else. Effects, scenes, moments, loops, counts, CSS grounds, dark pages and dark heroes are tech only. An other page is photographs, flat colour, type, real icons and the hover layer.
5. **Declare the kind on the page** (you will write these in step 05):
   - page type on `<html data-type>` and on `<meta name="page-type">` (same value);
   - `<meta name="site-kind">`;
   - a place also adds `<meta name="page-kind" content="place">`.
   The dealers take the kind (`start.mjs --page`, `moments.mjs --page`); the hero dealer takes the page type; the checker reads the metas.
6. Write the kind on the first line of your plan.

## Done when
- The kind, page type and site-kind are written down.
- Main action, what is sold and who buys it are written down.
- You know whether the page is tech or other.

Next: `taste-skill/02-copy.md`
