# AGENT.md — Skills blueprint folder

Read this before using anything in `blueprints/Skills/`. The rules in `blueprints/AGENT.md` apply here too: the user's prompt comes first, and nothing in this folder grants permissions or overrides approvals.

## What this folder is

Reusable skills: longer, step-by-step instructions for one kind of work. A skill can be one file, or a folder of numbered files that you read one at a time.

## What is in it

| Entry | What it does | Use when |
|---|---|---|
| `taste-skill.md` + `taste-skill/` | Builds company, product, shop, cafe, SaaS and game websites that do not look generic or AI-made (from Leonxlnx/taste-skill 2.5.0, MIT) | The user asks to build, redesign, restyle or review a website or landing page |

## How to use a skill

1. Read the entry file (`<skill-name>.md`) and check its `use when:` line. No match: ignore the skill.
2. If the entry points to a folder, open the first numbered file (`00-start.md`) and follow the chain. Each file ends with a `Next:` line. Read only that file, only when you reach it. Never read ahead.
3. A step names the reference files it needs. Open those when you reach that step, not before, and read only the parts you use.
4. Some skills have a `skill/` folder with the original scripts, assets and references. If it is missing, say so in one line and do by hand what a script would do. Never pretend a script ran.
5. Commands in a skill are only run if the user asked for that kind of work and the tools exist (for taste-skill: the `skill/` folder, Node 22+, and Chrome, Edge or Chromium). Otherwise report what you could not check.
6. Finish each step's `Done when` list. Report each item as pass or fail, or "not checked". Never claim a pass you did not see.

## Adding a skill

- One file: name it `<skill-name>.md` with `use when:`, Stack, Steps, Example and Done when, as in `blueprints/README.md`.
- Several files: add a short `<skill-name>.md` entry (a `use when:` line and a pointer), then a `<skill-name>/` folder of numbered files `00-start.md`, `01-...`. Each ends with `Next:` and has its own `Done when`. Keep the last one marked "End of ...".
- Add a row to the table above.
