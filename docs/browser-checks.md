# Browser checks

Lily can check a web page in the Browse section: what it logged, how fast it loaded, and a set of accessibility and layout checks. She reads the page; she does not click or type.

## How to use it

Ask in Chat or the editor, for example:

- `test https://example.com for problems`
- `check the current website` (the page already open in Browse)

The planner sends the task to the tester role. The first time a page is opened you are asked to approve it, with the address shown; the page already open needs no approval. The Browse section comes on screen while she checks, because a page that is not on screen has no size and layout checks would be wrong. If the app is in the background she says so and checks nothing.

## What is checked

- Console messages, failed requests and HTTP errors while the page loaded, and the load time.
- Images without alt text, form fields without a label, links or buttons with no readable name.
- Tap targets under 44 x 44 px, text under 12 px, low contrast text (approximate: backgrounds that are images or gradients are ignored).
- Viewport tag, page language, h1 count and sideways scrolling.

The checks are fixed code. The model only writes the review from the facts, and is told what could not be checked.

## What is not built

- Screenshots and design review by picture: the Model Gateway takes text only.
- Clicking and typing: needs a model tool-use loop and a separate login session for Lily, neither of which exists.
- Other browsers and large screens: the page runs in this phone's Chromium only.

## Safety

- Page text, titles, log lines and audit examples are written by other people. They are marked as data in the model's prompt, cut to a fixed size, and the block they sit in cannot be closed from inside.
- Opening a page runs its scripts with the browser's cookies, so every new page is approved by the person first.

## Where it lives

- `tools/BrowserBridge.kt`: what workers can read. `shell/BrowseBridge.kt`: the WebView version.
- `tools/AuditScript.kt`, `tools/AuditReport.kt`, `tools/JsResult.kt`: the in-page checks and how their answer is read.
- `shell/PageLog.kt`: the console and error log kept by `BrowseState`.
- `workers/BrowserTestWorker.kt`: approval, the fixed checks and the review.
- Wired in `LilyFactory.create` (`browser`, `browserGate`) and `EditorActivity`.

Not yet run on a device.
