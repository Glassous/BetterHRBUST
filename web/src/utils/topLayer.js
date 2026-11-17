let cachedDisplayTransition;

/**
 * Probe whether discrete `display` transitions actually animate (e.g. Firefox 129+
 * parses `transition-behavior: allow-discrete` without keeping `display` visible).
 */
export function canTransitionDisplay() {
  if (cachedDisplayTransition !== undefined) return cachedDisplayTransition;
  if (typeof window === 'undefined' || !window.CSS?.supports?.('transition-behavior', 'allow-discrete') || !document.body) {
    return false;
  }
  const probe = document.createElement('div');
  probe.style.cssText = 'transition: display 1s allow-discrete !important; display: block;';
  document.body.appendChild(probe);
  getComputedStyle(probe).display;
  probe.style.display = 'none';
  cachedDisplayTransition = getComputedStyle(probe).display === 'block';
  probe.remove();
  return cachedDisplayTransition;
}

export function supportsTopLayerExit() {
  return typeof window !== 'undefined' && !!window.CSS?.supports?.('overlay', 'auto') && canTransitionDisplay();
}

export function isPointerOutsideElement(event, el) {
  const rect = el.getBoundingClientRect();
  return event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom;
}

export function supportsAnchorPositionArea() {
  return typeof window !== 'undefined' && !!window.CSS?.supports?.('position-area', 'block-end');
}

/**
 * Fallback viewport-aware fixed coordinates for `[popover]` panels when CSS
 * Anchor Positioning (`position-area`) is unavailable (e.g. Firefox).
 */
export function computeAnchoredFixedStyle(triggerEl, panelEl, gap = 6) {
  if (!triggerEl || typeof window === 'undefined') return {};
  const rect = triggerEl.getBoundingClientRect();
  const margin = 8;
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const panelWidth = panelEl?.offsetWidth || Math.max(rect.width, 180);
  const panelHeight = panelEl?.offsetHeight || 220;

  const spaceBelow = vh - rect.bottom - margin;
  const spaceAbove = rect.top - margin;
  const openAbove = spaceBelow < panelHeight && spaceAbove > spaceBelow;
  const top = openAbove
    ? Math.max(margin, rect.top - gap - panelHeight)
    : Math.min(vh - margin - panelHeight, rect.bottom + gap);

  const left = Math.max(margin, Math.min(rect.left, vw - margin - panelWidth));
  return {
    position: 'fixed',
    inset: 'auto',
    margin: '0px',
    top: `${Math.round(top)}px`,
    left: `${Math.round(left)}px`
  };
}

/**
 * Close a modal `<dialog>`, waiting for its `[data-closing]` CSS exit transition
 * when native top-layer `overlay` + discrete `display` exit is unsupported.
 */
export async function closeModalDialog(dialog) {
  if (!dialog || !dialog.open) return;
  if (!supportsTopLayerExit()) {
    dialog.setAttribute('data-closing', '');
    const animations = dialog.getAnimations({ subtree: true });
    if (animations.length > 0) {
      await Promise.race([
        Promise.allSettled(animations.map(a => a.finished)),
        new Promise(resolve => setTimeout(resolve, 400))
      ]);
    }
    if (!dialog.hasAttribute('data-closing')) return;
    dialog.removeAttribute('data-closing');
  }
  if (dialog.open) dialog.close();
}
