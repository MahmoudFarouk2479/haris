// Talks to the local core. The session token arrives once in the URL fragment of the
// window the core opened; it is moved to sessionStorage and wiped from the address bar.
const TOKEN_KEY = 'haris.token';

const token = (() => {
  const match = location.hash.match(/token=([A-Za-z0-9_-]{20,})/);
  if (match) {
    sessionStorage.setItem(TOKEN_KEY, match[1]);
    history.replaceState(null, '', location.pathname);
  }
  return sessionStorage.getItem(TOKEN_KEY);
})();

export const hasToken = () => Boolean(token);
export const sessionToken = () => token;

/** POSTs raw bytes (microphone PCM) and returns the JSON reply. */
export async function postBinary(path, bytes) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'X-Haris-Token': token ?? '', 'Content-Type': 'application/octet-stream' },
    body: bytes,
  });
  const data = res.headers.get('content-type')?.includes('application/json') ? await res.json() : null;
  if (!res.ok) throw new ApiError(data?.error ?? `Request failed (${res.status})`);
  return data;
}

/** POSTs JSON and returns the raw response bytes (synthesised speech). */
export async function postForAudio(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'X-Haris-Token': token ?? '', 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new ApiError(`Speech failed (${res.status})`);
  return res.arrayBuffer();
}

export class ApiError extends Error {}

export async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(path, {
    method,
    headers: {
      'X-Haris-Token': token ?? '',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  const isJson = res.headers.get('content-type')?.includes('application/json');
  const data = isJson ? await res.json() : null;
  if (!res.ok) {
    throw new ApiError(data?.error ?? `Request failed (${res.status})`);
  }
  return data;
}

/**
 * Sends one message and streams the agent's progress. Server-sent events are read from a
 * fetch body (not EventSource) so the token can travel in a header.
 * `handlers` maps event names (meta, delta, tool, confirm, notice, security, done, error)
 * to callbacks.
 */
export async function streamChat(message, handlers) {
  let res;
  try {
    res = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'X-Haris-Token': token ?? '', 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
    });
  } catch {
    handlers.error?.({ message: 'The core is not responding.' });
    return;
  }
  if (res.status === 409) return handlers.error?.({ message: 'Still working on the previous message.' });
  if (!res.ok) return handlers.error?.({ message: `The core refused the request (${res.status}).` });
  await readEvents(res, handlers);
}

/** A long-lived stream of background events (reminders, notifications). Reconnects on its own. */
export async function subscribeEvents(handlers) {
  for (;;) {
    try {
      const res = await fetch('/api/events', { headers: { 'X-Haris-Token': token ?? '' } });
      if (res.ok) await readEvents(res, handlers);
    } catch {
      /* core restarting */
    }
    await new Promise((r) => setTimeout(r, 3000));
  }
}

async function readEvents(res, handlers) {
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let split;
    while ((split = buffer.indexOf('\n\n')) !== -1) {
      const raw = buffer.slice(0, split);
      buffer = buffer.slice(split + 2);
      const event = parseEvent(raw);
      if (event) handlers[event.name]?.(event.data);
    }
  }
}

function parseEvent(raw) {
  let name = 'message';
  const data = [];
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  if (!data.length) return null;
  try {
    return { name, data: JSON.parse(data.join('\n')) };
  } catch {
    return null;
  }
}
