// The conversation column. On wide windows (>= 1200 px) the conversation is a full-height column
// at the inline-end side that can be hidden ("voice mode": only the composer stays, floating under
// the emblem, and a tab on the edge re-opens it). Narrower windows keep the stacked layout, where
// the conversation is always visible. The pure helpers hold the rules so they can be tested without
// a browser; mountChatPanel only wires them to the page.

export const CHAT_SIDE_MIN_WIDTH = 1200;

export function chatLayoutFor(width) {
  return width >= CHAT_SIDE_MIN_WIDTH ? 'side' : 'stacked';
}

export function serializeChatOpen(open) {
  return open ? 'open' : 'closed';
}

/** Only an explicit "closed" hides the column; empty, blocked or garbage storage means open. */
export function restoreChatOpen(raw) {
  return raw !== 'closed';
}

/** What the page shows for a window width and the remembered choice. The composer is never hidden. */
export function chatViewFor(width, open) {
  const layout = chatLayoutFor(width);
  const side = layout === 'side';
  return { layout, hidden: side && !open, hideButton: side && open, tab: side && !open };
}

export function mountChatPanel({ doc = document, win = window, storageKey = 'haris.chat', onReveal = () => {} } = {}) {
  const hideButton = doc.getElementById('chat-hide');
  const tab = doc.getElementById('chat-show');
  const input = doc.getElementById('input');

  let saved = null;
  try {
    saved = win.localStorage.getItem(storageKey);
  } catch {
    /* storage blocked: start open */
  }
  let open = restoreChatOpen(saved);
  let shown = null;   // the last rendered view, to notice when the transcript appears or the layout changes

  const remember = () => {
    try {
      win.localStorage.setItem(storageKey, serializeChatOpen(open));
    } catch {
      /* storage blocked: the column still works, it is just not remembered */
    }
  };

  const render = () => {
    const view = chatViewFor(win.innerWidth, open);
    const focused = doc.activeElement;
    doc.body.classList.toggle('chat-side', view.layout === 'side');
    doc.body.classList.toggle('chat-hidden', view.hidden);
    hideButton.hidden = !view.hideButton;
    tab.hidden = !view.tab;
    for (const b of [hideButton, tab]) b.setAttribute('aria-expanded', String(!view.hidden));
    if (!view.hidden) tab.classList.remove('news');
    // A focused button that just disappeared (resize to the stacked layout) hands focus on.
    if ((focused === tab && tab.hidden) || (focused === hideButton && hideButton.hidden)) (view.tab ? tab : input).focus();
    const revealed = !view.hidden && (!shown || shown.hidden || shown.layout !== view.layout);
    shown = view;
    if (revealed) onReveal();
  };

  hideButton.addEventListener('click', () => {
    open = false;
    remember();
    render();
    tab.focus();
  });
  tab.addEventListener('click', () => {
    open = true;
    remember();
    render();
    input.focus();
  });
  win.addEventListener('resize', render);
  render();

  return {
    /** A gold dot on the tab: something new arrived while the conversation was hidden. */
    markNew() {
      if (shown?.hidden) tab.classList.add('news');
    },
    get open() {
      return open;
    },
  };
}
