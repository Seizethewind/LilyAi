# Browser checks

Lily can check a web page in the Browse section: what it logged, how fast it loaded, accessibility and layout checks, and how it looks. With separate approval she can also use the page (click and type) to test a flow.

## How to use it

Ask in Chat or the editor, for example:

- `test https://example.com for problems` (a review from the page's logs, checks and a screenshot)
- `check the current website` (the page already open in Browse)
- `click through the signup form at https://example.com/signup` (uses the page; see below)

The planner sends the task to the tester role. The first time a page is opened you are asked to approve it, with the address shown; the page already open needs no approval for a check. The Browse section comes on screen while she works, because a page that is not on screen has no size and layout checks would be wrong. If the app is in the background she says so and does nothing.

## What is checked

- Console messages, failed requests and HTTP errors while the page loaded, and the load time.
- Images without alt text, form fields without a label, links or buttons with no readable name.
- Tap targets under 44 x 44 px, text under 12 px, low contrast text (approximate: backgrounds that are images or gradients are ignored).
- Viewport tag, page language, h1 count and sideways scrolling.
- How the page looks, from a screenshot (below).

The checks are fixed code. The model only writes the review from the facts, and is told what could not be checked.

## Screenshots and how the page looks

A picture of what is on screen (not the whole page) is sent with the facts to the tester's model, through the router in the OpenAI image format.

- The tester's default model (`auto:fast`) may not be able to see images. Give your tester a model that can, in the AI section.
- Many routed models ignore a picture silently and then make up a design review. To catch that, a random code is drawn into the picture's corner and the model must start its reply with it. A reply without the code is thrown away, the review is made again from the facts alone, and a note says so.
- A capture that comes out all one colour is not sent.

## Using the page (click and type)

Only when the task asks for it (words like click, fill, submit, walk through, go through, interact, try out). It always asks first, and the question shows the address, the goal, the limits and whether this browser holds cookies for the site. If it does and you are logged in there, Lily would act as you: approve that only for sites you own or test.

How it works: each turn the model sees a numbered list of what can be clicked or typed into, and answers with one JSON action from it. It never writes script.

- **Only this site.** The browser is locked to the approved host while it runs. A move to another host is stopped, and the run ends if the page ends up elsewhere.
- **At most 10 steps**, two bad replies in a row end it.
- **Refused:** password, card, code and similar fields; buttons that look like paying, sending money or deleting (keyword based, so not complete); file pickers; `javascript:` links.
- **Fake data only:** the model is told to type test data such as test@example.com.
- **A page that changed** since the list was made is noticed (each action carries the item's name) and nothing is clicked.
- You get the list of steps taken, what the model found, and problems the page logged.

## What is not built

- The whole page in a screenshot (only what is on screen), other browsers and large screens.
- A separate login session for Lily. WebView shares cookies app-wide, so the only protection is the question above and the host lock.
- Choosing the screenshot model from a setting.

## Safety

- Page text, titles, log lines, item names and audit examples are written by other people. They are marked as data in the model's prompt, cut to a fixed size, and the block they sit in cannot be closed from inside.
- Opening a page runs its scripts with the browser's cookies, so every new page is approved first.

## Where it lives

- `tools/BrowserBridge.kt`: what workers can read and do. `shell/BrowseBridge.kt`: the WebView version (checks, screenshot, list, actions, host lock).
- `tools/AuditScript.kt`, `tools/AuditReport.kt`, `tools/JsResult.kt`: the in-page checks and how their answer is read.
- `tools/PageActions.kt`, `tools/PageElements.kt`: the list and the click and type scripts.
- `shell/PageLog.kt`: the console and error log kept by `BrowseState`, which also holds the host lock.
- `workers/BrowserTestWorker.kt`: approval, the fixed checks, the screenshot review. `workers/BrowserFlow.kt`: the click and type loop.
- `gateway/ModelGateway.kt`, `gateway/RouterModelGateway.kt`: pictures in a model request.
- Wired in `LilyFactory.create` (`browser`, `browserGate`) and `EditorActivity`.

Not yet run on a device.
