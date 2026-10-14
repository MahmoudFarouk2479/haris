// The icon rail and its slide-out drawers. drawerReducer holds every rule (one drawer at a time,
// pinning only on wide windows, outside clicks, Esc) so the rules can be tested without a browser;
// mountDrawers only wires them to the page.

export const DRAWERS = ['system', 'activity', 'voice', 'security'];
export const PIN_MIN_WIDTH = 1600;
export const CLOSED = Object.freeze({ open: null, pinned: false });

export function canPin(width) {
  return width >= PIN_MIN_WIDTH;
}

/** Pinned for real: a pinned drawer floats while the window is narrower than PIN_MIN_WIDTH. */
export function isPinned(state, width) {
  return Boolean(state.open && state.pinned && canPin(width));
}

export function drawerReducer(state, action, { width }) {
  switch (action.type) {
    case 'toggle':
      if (!DRAWERS.includes(action.id)) return state;
      if (state.open === action.id) return CLOSED;
      return { open: action.id, pinned: Boolean(state.pinned) && canPin(width) };
    case 'pin':
      if (!state.open || !canPin(width)) return state;
      return { open: state.open, pinned: !state.pinned };
    case 'outside':
      return state.open && !isPinned(state, width) ? CLOSED : state;
    case 'escape':
      return state.open ? CLOSED : state;
    default:
      return state;
  }
}

/** Only a pinned drawer is worth remembering between launches. */
export function serializeDrawerState(state) {
  return state.open && state.pinned ? JSON.stringify({ pinned: state.open }) : '';
}

export function restoreDrawerState(raw) {
  try {
    const value = JSON.parse(raw);
    if (value && DRAWERS.includes(value.pinned)) return { open: value.pinned, pinned: true };
  } catch {
    /* empty, blocked or garbage: start closed */
  }
  return CLOSED;
}

/** Esc belongs to an open dialog (the approval card denies on Esc), never to a drawer behind it. */
export function escapeClosesDrawer(doc) {
  return !doc.querySelector('dialog[open]');
}

export function mountDrawers({ doc = document, win = window, storageKey = 'haris.drawer' } = {}) {
  const width = () => win.innerWidth;
  let state = CLOSED;
  try {
    state = restoreDrawerState(win.localStorage.getItem(storageKey));
  } catch {
    state = CLOSED;
  }

  const render = () => {
    for (const id of DRAWERS) {
      const open = state.open === id;
      doc.getElementById(`drawer-${id}`).hidden = !open;
      const button = doc.querySelector(`[data-drawer="${id}"]`);
      button.setAttribute('aria-expanded', String(open));
      button.classList.toggle('on', open);
      if (open) button.classList.remove('news');
    }
    const pinned = isPinned(state, width());
    doc.body.classList.toggle('drawer-pinned', pinned);
    doc.body.classList.toggle('can-pin', canPin(width()));
    for (const b of doc.querySelectorAll('[data-pin]')) b.setAttribute('aria-pressed', String(pinned));
    try {
      win.localStorage.setItem(storageKey, serializeDrawerState(state));
    } catch {
      /* storage blocked: the layout still works, it is just not remembered */
    }
  };

  const dispatch = (action) => {
    state = drawerReducer(state, action, { width: width() });
    render();
  };

  for (const b of doc.querySelectorAll('[data-drawer]')) {
    b.addEventListener('click', (e) => {
      e.stopPropagation();
      dispatch({ type: 'toggle', id: b.dataset.drawer });
    });
  }
  for (const b of doc.querySelectorAll('[data-pin]')) b.addEventListener('click', () => dispatch({ type: 'pin' }));
  for (const b of doc.querySelectorAll('[data-drawer-close]')) b.addEventListener('click', () => dispatch({ type: 'escape' }));
  doc.addEventListener('pointerdown', (e) => {
    if (!e.target.closest('.drawer, .rail, dialog')) dispatch({ type: 'outside' });
  });
  doc.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && escapeClosesDrawer(doc)) dispatch({ type: 'escape' });
  });
  win.addEventListener('resize', render);
  render();

  return {
    /** A gold dot on a closed drawer's icon: something new is inside. */
    markNew(id) {
      const button = doc.querySelector(`[data-drawer="${id}"]`);
      if (button && state.open !== id) button.classList.add('news');
    },
    get state() {
      return state;
    },
  };
}
