package ai.lily.app.tools

/**
 * The scripts Lily runs inside a page. Each returns JSON text. Every check is capped (elements looked at, examples kept), so a huge
 * page cannot make it slow. Page scripts run in the same world and could change what these see, so what they return is page text:
 * untrusted, like everything else from the page.
 * shortcut: contrast ignores background images, gradients and text transparency, when that matters use a screenshot and a vision model.
 */
object AuditScript {
    /** The address, title and readable text of the page. */
    const val SNAPSHOT = """(function () {
  try {
    return JSON.stringify({
      url: location.href,
      title: document.title || '',
      text: document.body ? document.body.innerText.slice(0, 20000) : ''
    });
  } catch (e) {
    return null;
  }
})()"""

    /** Accessibility and layout checks. */
    const val SOURCE = """(function () {
  var MAX_ELEMENTS = 400, MAX_EXAMPLES = 5;
  function label(el) {
    var s = el.tagName.toLowerCase();
    if (el.id) return s + '#' + el.id;
    var c = typeof el.className === 'string' ? el.className.trim().split(/\s+/)[0] : '';
    return c ? s + '.' + c : s;
  }
  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    var st = getComputedStyle(el);
    return st.visibility !== 'hidden' && st.display !== 'none';
  }
  function check(id, list, test, describe) {
    var bad = [], n = 0;
    for (var i = 0; i < list.length && i < MAX_ELEMENTS; i++) {
      n++;
      if (test(list[i])) bad.push(describe ? describe(list[i]) : label(list[i]));
    }
    return { id: id, checked: n, bad: bad.length, examples: bad.slice(0, MAX_EXAMPLES) };
  }
  function parse(c) {
    var m = /rgba?\(([^)]+)\)/.exec(c);
    if (!m) return null;
    var p = m[1].trim().split(/[\s,\/]+/).map(parseFloat);
    var a = p.length > 3 && !isNaN(p[3]) ? p[3] : 1;
    if (a > 1) a = a / 100;
    return { r: p[0], g: p[1], b: p[2], a: a };
  }
  function lum(c) {
    function f(v) { v = v / 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
    return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
  }
  function backgroundOf(el) {
    for (var e = el; e; e = e.parentElement) {
      var c = parse(getComputedStyle(e).backgroundColor);
      if (c && c.a >= 0.9) return c;
    }
    return { r: 255, g: 255, b: 255, a: 1 };
  }
  function ratio(el) {
    var fg = parse(getComputedStyle(el).color);
    if (!fg) return null;
    var a = lum(fg) + 0.05, b = lum(backgroundOf(el)) + 0.05;
    return a > b ? a / b : b / a;
  }
  function hasOwnText(el) {
    for (var n = el.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 3 && n.nodeValue.trim()) return true;
    }
    return false;
  }
  try {
    var texts = [], all = document.body ? document.body.getElementsByTagName('*') : [];
    for (var i = 0; i < all.length && i < 5000 && texts.length < MAX_ELEMENTS; i++) {
      var el = all[i];
      if (el.tagName !== 'SCRIPT' && el.tagName !== 'STYLE' && hasOwnText(el) && visible(el)) texts.push(el);
    }
    var checks = [];
    checks.push(check('image-alt', document.images, function (im) { return !im.hasAttribute('alt'); },
      function (im) { return (im.getAttribute('src') || '').slice(-60); }));
    var fields = document.querySelectorAll('input:not([type=hidden]):not([type=submit]):not([type=button]):not([type=reset]):not([type=image]), select, textarea');
    checks.push(check('field-label', fields, function (f) {
      if (f.getAttribute('aria-label') || f.getAttribute('aria-labelledby') || f.getAttribute('title')) return false;
      if (f.closest('label')) return false;
      if (f.id && document.querySelector('label[for="' + CSS.escape(f.id) + '"]')) return false;
      return true;
    }));
    checks.push(check('empty-link', document.querySelectorAll('a[href], button'), function (a) {
      if ((a.textContent || '').trim() || a.getAttribute('aria-label') || a.getAttribute('title')) return false;
      var img = a.querySelector('img[alt]');
      return !(img && img.getAttribute('alt').trim());
    }));
    checks.push(check('tap-target', document.querySelectorAll('a[href], button, input:not([type=hidden]), select, textarea, [role=button]'), function (el) {
      if (!visible(el)) return false;
      var r = el.getBoundingClientRect();
      return r.width < 44 || r.height < 44;
    }, function (el) {
      var r = el.getBoundingClientRect();
      return label(el) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height);
    }));
    checks.push(check('small-text', texts, function (el) { return parseFloat(getComputedStyle(el).fontSize) < 12; },
      function (el) { return label(el) + ' ' + getComputedStyle(el).fontSize; }));
    checks.push(check('contrast', texts, function (el) {
      var r = ratio(el);
      if (r === null) return false;
      var st = getComputedStyle(el), size = parseFloat(st.fontSize);
      var large = size >= 24 || (size >= 18.66 && parseInt(st.fontWeight, 10) >= 700);
      return r < (large ? 3 : 4.5);
    }, function (el) { return label(el) + ' ' + (Math.round(ratio(el) * 10) / 10) + ':1'; }));
    return JSON.stringify({
      width: window.innerWidth,
      height: window.innerHeight,
      lang: !!document.documentElement.getAttribute('lang'),
      viewportMeta: !!document.querySelector('meta[name=viewport]'),
      h1: document.querySelectorAll('h1').length,
      overflowX: document.documentElement.scrollWidth > window.innerWidth + 1,
      checks: checks
    });
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})()"""
}
