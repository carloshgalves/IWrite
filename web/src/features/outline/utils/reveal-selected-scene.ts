/**
 * Reveals the currently selected scene using only the outline's own scrollport.
 *
 * Element.scrollIntoView() is intentionally not used: it can move ancestor scrollports
 * (including the workspace/editor) and cause the jump reported in #249.
 *
 * Returns true only when a selected scene is mounted in a measurable scrollport. A collapsed
 * ancestor hides the row entirely, so the caller must retry after expanding that ancestor.
 */
export function revealSelectedOutlineScene(scrollport: HTMLElement): boolean {
  const selected = scrollport.querySelector<HTMLButtonElement>('button[aria-current="location"]');
  if (!selected || scrollport.clientHeight <= 0) {
    return false;
  }

  const viewport = scrollport.getBoundingClientRect();
  const item = selected.getBoundingClientRect();
  const margin = 8;

  if (item.top < viewport.top) {
    scrollport.scrollTop = Math.max(0, scrollport.scrollTop + item.top - viewport.top - margin);
  } else if (item.bottom > viewport.bottom) {
    scrollport.scrollTop += item.bottom - viewport.bottom + margin;
  }

  return true;
}
