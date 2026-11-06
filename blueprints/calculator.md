# calculator
use when: the user asks for a calculator (any platform) and gives no other design

## Stack
Default: one HTML file, plain JS, no dependencies. If the user names another stack, keep the same logic and behaviour.

## Steps
1. Keep the math in one pure function `calc(expr: string)` that returns a number or throws. The UI only calls it.
2. Parse with a small tokenizer + precedence parser (`* /` before `+ -`, parentheses, unary minus, decimals). Never use `eval`.
3. UI: a display, buttons `0-9 . + - × ÷ ( ) = C`, and a backspace.
4. Errors show `Error` on the display and do not crash. `C` clears the error.
5. Format results: no floating-point noise (`0.1+0.2` shows `0.3`).

## Example (logic only)
```js
function calc(src) {
  const t = src.replace(/×/g, '*').replace(/÷/g, '/').match(/\d+\.?\d*|\.\d+|[-+*/()]/g);
  if (!t || t.join('') !== src.replace(/×/g, '*').replace(/÷/g, '/').replace(/\s/g, '')) throw new Error('bad input');
  let i = 0;
  const peek = () => t[i], next = () => t[i++];
  function expr() { let v = term(); while (peek() === '+' || peek() === '-') v = next() === '+' ? v + term() : v - term(); return v; }
  function term() { let v = factor(); while (peek() === '*' || peek() === '/') { const op = next(), r = factor(); if (op === '/' && r === 0) throw new Error('div by zero'); v = op === '*' ? v * r : v / r; } return v; }
  function factor() { const x = next(); if (x === '-') return -factor(); if (x === '(') { const v = expr(); if (next() !== ')') throw new Error('missing )'); return v; } const n = parseFloat(x); if (Number.isNaN(n)) throw new Error('bad input'); return n; }
  const out = expr();
  if (i !== t.length) throw new Error('bad input');
  return Math.round(out * 1e10) / 1e10;
}
```

## Done when
- `2+3*4` = 14
- `(1+2)*3` = 9
- `10/4` = 2.5
- `0.1+0.2` = 0.3
- `-5+2` = -3
- `5/0` = Error
- `2+` = Error
- `abc` = Error
