// On-screen search keyboard: six keys per row, driven with the D-pad, so
// searching never depends on the TV's own (slow, full-screen) keyboard.
// The text field stays in the page: OK on it still opens the system keyboard
// and a USB or phone keyboard types into it as before.
export const SEARCH_KEYBOARD_COLUMNS = 6;
const CHARACTER_KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".split("");
/** Action keys under the grid, with the grid columns each one spans. */
export const SEARCH_KEYBOARD_ACTIONS = Object.freeze([
  Object.freeze({ id: "space", columns: [0, 1, 2] }),
  Object.freeze({ id: "backspace", columns: [3] }),
  Object.freeze({ id: "clear", columns: [4, 5] })
]);
/** Key ids row by row: character rows first, the action row last. */
export function getSearchKeyboardRows() {
  const rows = [];
  for (let index = 0; index < CHARACTER_KEYS.length; index += SEARCH_KEYBOARD_COLUMNS) {
    rows.push(CHARACTER_KEYS.slice(index, index + SEARCH_KEYBOARD_COLUMNS));
  }
  rows.push(SEARCH_KEYBOARD_ACTIONS.map((action) => action.id));
  return rows;
}
/** The text after pressing `key` ("a", "space", "backspace", "clear"). */
export function applySearchKeyboardKey(value, key) {
  const text = String(value || "");
  const id = String(key || "");
  if (id === "clear") {
    return "";
  }
  if (id === "backspace") {
    // Remove one whole character, including astral ones typed elsewhere.
    return Array.from(text).slice(0, -1).join("");
  }
  if (id === "space") {
    // No leading or doubled spaces: they never change a search.
    return !text || text.endsWith(" ") ? text : `${text} `;
  }
  return id.length === 1 ? text + id : text;
}
/**
 * Where a D-pad press lands inside the keyboard.
 * Returns { row, col } or an exit: "header" (up from the top row), "results"
 * (right from the last column), "sidebar" (left from the first) or null (stay).
 */
export function moveInSearchKeyboard(rows, row, col, direction) {
  const lastRow = rows.length - 1;
  const actionRow = lastRow;
  const currentRow = Math.max(0, Math.min(lastRow, Number(row) || 0));
  const currentCol = Math.max(0, Math.min(rows[currentRow].length - 1, Number(col) || 0));
  if (direction === "left") {
    return currentCol > 0 ? { row: currentRow, col: currentCol - 1 } : "sidebar";
  }
  if (direction === "right") {
    return currentCol < rows[currentRow].length - 1
      ? { row: currentRow, col: currentCol + 1 }
      : "results";
  }
  if (direction === "up") {
    if (currentRow === 0) {
      return "header";
    }
    if (currentRow === actionRow) {
      // Back up into the grid above the middle of the action key.
      const columns = SEARCH_KEYBOARD_ACTIONS[currentCol].columns;
      return { row: currentRow - 1, col: columns[Math.floor((columns.length - 1) / 2)] };
    }
    return { row: currentRow - 1, col: currentCol };
  }
  if (direction === "down") {
    if (currentRow === actionRow) {
      return null;
    }
    if (currentRow + 1 === actionRow) {
      const actionIndex = SEARCH_KEYBOARD_ACTIONS.findIndex((action) =>
        action.columns.includes(currentCol)
      );
      return { row: actionRow, col: Math.max(0, actionIndex) };
    }
    return { row: currentRow + 1, col: Math.min(currentCol, rows[currentRow + 1].length - 1) };
  }
  return null;
}
const BACKSPACE_ICON =
  '<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6-7z"></path><path d="m12 9.5 5 5M17 9.5l-5 5"></path></svg>';
/**
 * Keyboard markup. `labels` supplies the translated action names:
 * { space, backspace, clear, keyboard }.
 */
export function renderSearchKeyboard(labels = {}) {
  const escape = (value) =>
    String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/"/g, "&quot;");
  const rows = getSearchKeyboardRows();
  const actionRow = rows.length - 1;
  const keys = rows
    .map((row, rowIndex) =>
      row
        .map((key, colIndex) => {
          const isAction = rowIndex === actionRow;
          const label = isAction ? labels[key] || key : key;
          const content = key === "backspace" ? BACKSPACE_ICON : escape(label);
          return (
            `<button class="search-key focusable${isAction ? ` search-key-action search-key-${key}` : ""}" ` +
            `type="button" data-action="searchKey" data-key="${escape(key)}" ` +
            `data-key-row="${rowIndex}" data-key-col="${colIndex}" aria-label="${escape(label)}">${content}</button>`
          );
        })
        .join("")
    )
    .join("");
  return `<div class="search-keyboard tv-keyboard" role="group" aria-label="${escape(labels.keyboard || "Keyboard")}">${keys}</div>`;
}
