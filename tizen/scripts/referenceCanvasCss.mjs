// One reference canvas: every length the app ships is a px value on a
// 1080-tall canvas, and js/platform/tvViewport.js maps that canvas onto the
// panel. The stylesheets inherited from the upstream TV client were written
// against the window instead (vw, vh, min(), clamp(), width media queries), so
// this pass resolves them at build time:
//
//   100vw / 100vh           -> the live canvas size (--tv-layout-width/height)
//   large layout extents    -> a share of the live canvas (width: 60vw)
//   every other vw / vh     -> px at 1920x1080 (font sizes, gaps, radii)
//   min() / max() / clamp() -> their value at 1920x1080, when it is constant
//   @media (min-width: ...) -> kept or dropped for a 1920x1080 canvas
//
// The result renders exactly what a 1920x1080 TV rendered before, stays the
// same size on wider canvases, and needs no CSS math on Tizen 4 (Chromium 56).
export const REFERENCE_CANVAS = Object.freeze({ width: 1920, height: 1080 });
const LAYOUT_WIDTH_VAR = `var(--tv-layout-width, ${REFERENCE_CANVAS.width}px)`;
const LAYOUT_HEIGHT_VAR = `var(--tv-layout-height, ${REFERENCE_CANVAS.height}px)`;
// Properties that describe how much of the screen an element takes. A large
// viewport share on these follows the canvas (so a panel still spans 60% of a
// 21:9 canvas, or of the smaller canvas the "Large" interface size lays out).
const EXTENT_PROPERTIES = new Set([
  "width",
  "height",
  "min-width",
  "max-width",
  "min-height",
  "max-height",
  "top",
  "right",
  "bottom",
  "left",
  "flex-basis"
]);
const PROPORTIONAL_THRESHOLD = 20;
const HAS_VIEWPORT_UNIT = /\d(?:vw|vh)\b/;
const HAS_MATH_FUNCTION = /\b(?:min|max|clamp)\(/;
const VIEWPORT_UNIT_PATTERN = /(-?\d*\.?\d+)(vw|vh)\b/g;
const NUMBER_PATTERN = /^([+-]?\d*\.?\d+)(px)?(?![\w%.])/;
const FUNCTION_START_PATTERN = /^(calc|min|max|clamp)\(/;
function formatNumber(value) {
  const rounded = Math.round(value * 1000) / 1000;
  return String(Object.is(rounded, -0) ? 0 : rounded);
}
function formatPx(value) {
  return `${formatNumber(value)}px`;
}
function viewportUnitToPx(amount, unit) {
  const reference = unit === "vw" ? REFERENCE_CANVAS.width : REFERENCE_CANVAS.height;
  return (Number(amount) * reference) / 100;
}
/** Index of the parenthesis that closes the one at `openIndex`, or -1. */
function findClosingParen(text, openIndex) {
  let depth = 0;
  for (let index = openIndex; index < text.length; index += 1) {
    const char = text[index];
    if (char === "(") {
      depth += 1;
    } else if (char === ")") {
      depth -= 1;
      if (depth === 0) return index;
    }
  }
  return -1;
}
function splitTopLevel(text, separator) {
  const parts = [];
  let depth = 0;
  let current = "";
  for (const char of text) {
    if (char === "(") depth += 1;
    else if (char === ")") depth -= 1;
    if (char === separator && depth === 0) {
      parts.push(current);
      current = "";
    } else {
      current += char;
    }
  }
  parts.push(current);
  return parts.map((part) => part.trim());
}
/**
 * Parse a constant length expression (`calc`, `min`, `max`, `clamp`, + - * /).
 * Returns { px, unitless } or null when anything in it is not a constant.
 */
function parseConstantExpression(source) {
  let position = 0;
  const skipSpaces = () => {
    while (position < source.length && /\s/.test(source[position])) position += 1;
  };
  const parsePrimary = () => {
    skipSpaces();
    const rest = source.slice(position);
    const fn = FUNCTION_START_PATTERN.exec(rest);
    if (fn || rest[0] === "(") {
      const open = position + (fn ? fn[1].length : 0);
      const close = findClosingParen(source, open);
      if (close < 0) return null;
      const inner = source.slice(open + 1, close);
      position = close + 1;
      if (!fn || fn[1] === "calc") {
        return parseConstantExpression(inner);
      }
      const args = splitTopLevel(inner, ",").map(parseConstantExpression);
      if (!args.length || args.some((arg) => arg === null || arg.unitless)) return null;
      const values = args.map((arg) => arg.px);
      if (fn[1] === "min") return { px: Math.min(...values), unitless: false };
      if (fn[1] === "max") return { px: Math.max(...values), unitless: false };
      if (values.length !== 3) return null;
      return { px: Math.max(values[0], Math.min(values[2], values[1])), unitless: false };
    }
    const number = NUMBER_PATTERN.exec(rest);
    if (!number) return null;
    position += number[0].length;
    const amount = Number(number[1]);
    if (!Number.isFinite(amount)) return null;
    // A bare 0 is a valid length.
    return { px: amount, unitless: !number[2] && amount !== 0 };
  };
  const parseProduct = () => {
    let left = parsePrimary();
    while (left) {
      skipSpaces();
      const operator = source[position];
      if (operator !== "*" && operator !== "/") break;
      position += 1;
      const right = parsePrimary();
      if (!right) return null;
      if (operator === "*") {
        if (!left.unitless && !right.unitless) return null;
        left = { px: left.px * right.px, unitless: left.unitless && right.unitless };
      } else {
        if (!right.unitless || right.px === 0) return null;
        left = { px: left.px / right.px, unitless: left.unitless };
      }
    }
    return left;
  };
  let result = parseProduct();
  while (result) {
    skipSpaces();
    const operator = source[position];
    if (operator !== "+" && operator !== "-") break;
    position += 1;
    const right = parseProduct();
    if (!right || result.unitless !== right.unitless) return null;
    result = {
      px: operator === "+" ? result.px + right.px : result.px - right.px,
      unitless: result.unitless
    };
  }
  skipSpaces();
  return result && position === source.length ? result : null;
}
/** A constant length expression in px, or null when it is not constant. */
export function evaluatePxExpression(expression) {
  const result = parseConstantExpression(String(expression || "").trim());
  return result && !result.unitless ? result.px : null;
}
/** Replace every statically computable min()/max()/clamp()/calc() by its px value. */
function collapseConstantMath(value) {
  let result = "";
  let index = 0;
  const pattern = /\b(calc|min|max|clamp)\(/g;
  while (index < value.length) {
    pattern.lastIndex = index;
    const match = pattern.exec(value);
    if (!match) {
      result += value.slice(index);
      break;
    }
    const open = match.index + match[1].length;
    const close = findClosingParen(value, open);
    if (close < 0) {
      result += value.slice(index);
      break;
    }
    const px = evaluatePxExpression(value.slice(match.index, close + 1));
    result += value.slice(index, match.index);
    if (px !== null) {
      result += formatPx(px);
    } else {
      // Keep the function, but still collapse anything constant inside it.
      result += `${match[1]}(${collapseConstantMath(value.slice(open + 1, close))})`;
    }
    index = close + 1;
  }
  return result;
}
/** True when `offset` sits inside a call to one of `names` (e.g. "min|max|clamp"). */
function isInsideFunction(value, offset, names) {
  const pattern = new RegExp(`\\b(${names})\\(`, "g");
  let match;
  while ((match = pattern.exec(value))) {
    const open = match.index + match[1].length;
    const close = findClosingParen(value, open);
    if (close >= 0 && offset > open && offset < close) return true;
  }
  return false;
}
/**
 * Resolve one declaration value against the reference canvas.
 * `prop` decides whether a large viewport share stays proportional.
 */
export function toReferenceCanvasValue(value, prop = "") {
  const source = String(value || "");
  if (!HAS_VIEWPORT_UNIT.test(source) && !HAS_MATH_FUNCTION.test(source)) {
    return source;
  }
  const extentProperty = EXTENT_PROPERTIES.has(String(prop || "").toLowerCase());
  const replaced = source.replace(VIEWPORT_UNIT_PATTERN, (match, amount, unit, offset) => {
    const share = Number(amount);
    const insideMath = isInsideFunction(source, offset, "min|max|clamp");
    const layoutVar = unit === "vw" ? LAYOUT_WIDTH_VAR : LAYOUT_HEIGHT_VAR;
    if (!insideMath && share === 100) {
      return layoutVar;
    }
    if (!insideMath && extentProperty && Math.abs(share) >= PROPORTIONAL_THRESHOLD) {
      // Inside an existing calc() a plain group is enough (and older engines
      // are happier without nested calc()).
      const product = `${layoutVar} * ${formatNumber(share / 100)}`;
      return isInsideFunction(source, offset, "calc") ? `(${product})` : `calc(${product})`;
    }
    return formatPx(viewportUnitToPx(amount, unit));
  });
  return collapseConstantMath(replaced);
}
const MEDIA_FEATURE_PATTERN = /\(\s*(min|max)-(width|height)\s*:\s*(-?\d*\.?\d+)px\s*\)/gi;
/**
 * Decide a media query for the 1920x1080 reference canvas.
 * Returns true (always applies), false (never applies) or null (not a pure
 * width/height query: leave it to the browser).
 */
export function evaluateReferenceMediaQuery(params) {
  const queries = splitTopLevel(String(params || ""), ",").filter(Boolean);
  if (!queries.length) return null;
  let anyTrue = false;
  for (const query of queries) {
    const remainder = query
      .replace(MEDIA_FEATURE_PATTERN, "")
      .replace(/\band\b/gi, "")
      .replace(/\b(?:screen|all)\b/gi, "")
      .trim();
    if (remainder) return null;
    let matched = false;
    let holds = true;
    query.replace(MEDIA_FEATURE_PATTERN, (match, bound, dimension, amount) => {
      matched = true;
      const reference =
        dimension.toLowerCase() === "width" ? REFERENCE_CANVAS.width : REFERENCE_CANVAS.height;
      const limit = Number(amount);
      if (bound.toLowerCase() === "min" ? reference < limit : reference > limit) {
        holds = false;
      }
      return match;
    });
    if (!matched) return null;
    if (holds) anyTrue = true;
  }
  return anyTrue;
}
/** PostCSS plugin applying the rules above to a stylesheet. */
export function referenceCanvasPlugin() {
  return {
    postcssPlugin: "starlit-reference-canvas",
    Once(root) {
      root.walkAtRules("media", (atRule) => {
        const verdict = evaluateReferenceMediaQuery(atRule.params);
        if (verdict === true) {
          atRule.replaceWith(atRule.nodes || []);
        } else if (verdict === false) {
          atRule.remove();
        }
      });
      root.walkDecls((decl) => {
        const next = toReferenceCanvasValue(decl.value, decl.prop);
        if (next !== decl.value) {
          decl.value = next;
        }
      });
    }
  };
}
referenceCanvasPlugin.postcss = true;
