package ai.lily.app.tools

import org.json.JSONObject

/**
 * The scripts that list what a page offers and do one click or one piece of typing. Nothing else can be done to a page: the model
 * never writes script, it only picks an item from the list. The list and the actions share their helpers, so item numbers agree, and
 * an action carries the item's name: a page that changed since the list was made is refused instead of clicking the wrong thing.
 * shortcut: the refusals are keyword based (password, card, pay, delete), when that is not enough use it only on sites you own.
 */
object PageActions {
    private const val HELPERS = """
  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    var st = getComputedStyle(el);
    return st.visibility !== 'hidden' && st.display !== 'none';
  }
  function isField(el) { return el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT'; }
  function labelOf(el) {
    var t = '';
    if (el.tagName === 'INPUT' && /^(submit|button|reset)${'$'}/i.test(el.type)) {
      t = el.value;
    } else if (el.getAttribute('aria-label')) {
      t = el.getAttribute('aria-label');
    } else if (isField(el)) {
      var l = el.id ? document.querySelector('label[for="' + CSS.escape(el.id) + '"]') : null;
      var w = el.closest('label');
      t = (l && l.innerText) || (w && w.innerText) || el.getAttribute('placeholder') || el.getAttribute('name') || el.id || '';
    } else {
      t = el.innerText || el.textContent || el.getAttribute('title') || '';
    }
    return String(t).replace(/\s+/g, ' ').trim().slice(0, 40);
  }
  function list() {
    var all = document.querySelectorAll('a[href], button, input:not([type=hidden]), select, textarea, [role=button], [onclick]');
    var out = [];
    for (var i = 0; i < all.length && out.length < 60; i++) {
      if (visible(all[i])) out.push(all[i]);
    }
    return out;
  }
"""

    private const val ELEMENTS_BODY = """
  try {
    return JSON.stringify(list().map(function (el, i) {
      var type = (el.getAttribute('type') || '').toLowerCase();
      return {
        i: i,
        tag: el.tagName.toLowerCase(),
        type: type,
        label: labelOf(el),
        hint: (el.getAttribute('placeholder') || '').slice(0, 40),
        value: (isField(el) && type !== 'password') ? String(el.value || '').slice(0, 40) : '',
        href: el.tagName === 'A' ? (el.getAttribute('href') || '').slice(0, 60) : ''
      };
    }));
  } catch (e) {
    return null;
  }
"""

    private const val ACTION_BODY = """
  function fail(kind, why) { return JSON.stringify({ ok: false, kind: kind, why: why }); }
  var SENSITIVE_FIELD = /pass|card|cvv|cvc|ccnum|ssn|social.?sec|otp|one-time|iban|\bpin\b/i;
  var SENSITIVE_BUTTON = /\b(pay|buy now|purchase|place order|confirm order|delete|remove account|close account|transfer|send money|donate)\b/i;
  try {
    var el = list()[args.i];
    if (!el) return fail('error', 'there is no item ' + args.i + ' any more');
    if (labelOf(el) !== args.label) return fail('error', 'the page changed: item ' + args.i + ' is now named ' + labelOf(el));
    var tag = el.tagName.toLowerCase();
    var type = (el.getAttribute('type') || '').toLowerCase();
    if (args.kind === 'type') {
      if (tag !== 'input' && tag !== 'textarea') return fail('error', 'item ' + args.i + ' is not a text field');
      if (tag === 'input' && ['', 'text', 'email', 'search', 'tel', 'url', 'number'].indexOf(type) < 0) {
        return fail('refused', 'only plain text fields can be typed into');
      }
      var hay = [el.getAttribute('name'), el.id, el.getAttribute('autocomplete'), labelOf(el), el.getAttribute('placeholder')].join(' ');
      if (SENSITIVE_FIELD.test(hay)) return fail('refused', 'this looks like a password, card or code field');
      el.focus();
      var proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, args.text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return JSON.stringify({ ok: true });
    }
    if (type === 'file') return fail('refused', 'file pickers are not used');
    if (SENSITIVE_BUTTON.test(labelOf(el))) return fail('refused', 'this looks like paying, sending money or deleting');
    if (tag === 'a' && /^\s*javascript:/i.test(el.getAttribute('href') || '')) return fail('refused', 'this link runs a script');
    el.scrollIntoView({ block: 'center' });
    el.click();
    return JSON.stringify({ ok: true });
  } catch (e) {
    return fail('error', String(e));
  }
"""

    /** The script that lists what can be clicked or typed into. */
    fun elementsScript(): String = "(function () {\n" + HELPERS + ELEMENTS_BODY + "\n})()"

    /** The script that does [action]. The values are written as JSON, so nothing in them can break out of the script. */
    fun actionScript(action: PageAction): String {
        val args = JSONObject()
        when (action) {
            is PageAction.Click -> args.put("kind", "click").put("i", action.index).put("label", action.label)
            is PageAction.Type -> args.put("kind", "type").put("i", action.index).put("label", action.label).put("text", action.text)
        }
        return "(function (args) {\n" + HELPERS + ACTION_BODY + "\n})(" + args + ")"
    }

    /** What an action script answered, as a short line starting with ok, refused or error. The page wrote the reason, so it is cut. */
    fun result(raw: String?): String {
        val text = JsResult.text(raw) ?: return "error: the page gave no answer"
        return runCatching {
            val o = JSONObject(text)
            if (o.optBoolean("ok")) {
                "ok"
            } else {
                val kind = if (o.optString("kind") == "refused") "refused" else "error"
                "$kind: ${flatten(o.optString("why"), 200)}"
            }
        }.getOrDefault("error: the page gave an answer that could not be read")
    }
}
