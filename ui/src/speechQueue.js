/**
 * Turns a stream of sentences into as few speech requests as possible, one at a time.
 *
 * The first sentence goes out at once, so speech starts quickly. Sentences that arrive while a
 * request is running wait and leave together as one request when it finishes. A cloud voice with
 * a per-minute quota (Gemini) then gets two or three requests per reply instead of one per
 * sentence fired all at once, which is what tripped its rate limit.
 */
export class SpeechQueue {
  /**
   * @param {(text: string) => Promise<ArrayBuffer|null>} request fetches audio for some text
   * @param {(audio: Promise<ArrayBuffer|null>) => void} onReady receives each request's audio, in order
   * @param {number} maxChars largest text sent in one request
   */
  constructor(request, onReady, maxChars = 1200) {
    this.request = request;
    this.onReady = onReady;
    this.maxChars = maxChars;
    this.buffer = [];
    this.inFlight = false;
  }

  push(text) {
    const clean = text.trim();
    if (!clean) return;
    this.buffer.push(clean);
    this.pump();
  }

  /** Barge-in: forget what has not been sent. A request already running finishes on its own. */
  clear() {
    this.buffer.length = 0;
  }

  pump() {
    if (this.inFlight || this.buffer.length === 0) return;
    let batch = this.buffer.shift().slice(0, this.maxChars);
    while (this.buffer.length && batch.length + 1 + this.buffer[0].length <= this.maxChars) {
      batch += ' ' + this.buffer.shift();
    }
    this.inFlight = true;
    let sent;
    try {
      sent = Promise.resolve(this.request(batch));   // starts now, not on a later tick
    } catch (e) {
      sent = Promise.reject(e);
    }
    const audio = sent
      .catch(() => null)
      .finally(() => {
        this.inFlight = false;
        this.pump();
      });
    this.onReady(audio);
  }
}
