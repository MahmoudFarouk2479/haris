// Mixed Arabic and Latin text in one line, e.g. the Arabic status "● Gemini (Piper احتياطي)".
//
// In a right-to-left line the browser groups "Gemini (Piper" into one left-to-right run, so the
// parentheses and words land in the wrong places. Isolating each Latin run in a <bdi> makes it a
// single neutral unit, and the line keeps the order it was written in. In a left-to-right line the
// isolation changes nothing.

/** Latin words (with digits, and spaces, dots or hyphens between them): engine names, "GPU", "hey haris". */
const LATIN_RUN = /[A-Za-z][A-Za-z0-9]*(?:[ .-][A-Za-z0-9]+)*/g;

/** Splits text into plain parts and Latin runs: [{ text, latin }]. Joined back, the parts are the text. */
export function bidiParts(value) {
  const text = String(value);
  const parts = [];
  let at = 0;
  for (const m of text.matchAll(LATIN_RUN)) {
    if (m.index > at) parts.push({ text: text.slice(at, m.index), latin: false });
    parts.push({ text: m[0], latin: true });
    at = m.index + m[0].length;
  }
  if (at < text.length) parts.push({ text: text.slice(at), latin: false });
  return parts;
}

/** Sets an element's text with each Latin run in its own <bdi> (DOM APIs only, never innerHTML). */
export function setBidiText(el, text) {
  const doc = el.ownerDocument;
  el.replaceChildren(...bidiParts(text).map((p) => {
    if (!p.latin) return doc.createTextNode(p.text);
    const bdi = doc.createElement('bdi');
    bdi.textContent = p.text;
    return bdi;
  }));
}
