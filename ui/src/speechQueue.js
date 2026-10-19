/** Each batch may be at most this many times longer than the one before it (see SpeechQueue). */
export const BATCH_GROWTH = 3;

/**
 * Turns a stream of sentences into as few speech requests as possible, one at a time.
 *
 * The first sentence goes out at once, so speech starts quickly. Sentences that arrive while a
 * request is running wait and leave together as one request when it finishes, so the next batch is
 * always being fetched while the one before it plays, and never more than one request is running.
 * A cloud voice with a per-minute quota (Gemini) then gets a few requests per reply instead of one
 * per sentence fired all at once, which is what tripped its rate limit.
 *
 * Batches grow: each is at most BATCH_GROWTH times the length of the one before it. A cloud voice
 * takes longer for longer text, so a short first sentence followed by the whole rest of the reply
 * left the speaker silent for seconds while the long batch was made. Growing step by step, each
 * batch is ready about when the one before it has finished playing. When nothing is waiting or
 * running (the reply is over, or the user broke in) the next batch starts small again.
 *
 * The first batch waits for about FIRST_BATCH_CHARS of text. A first sentence of a word or two
 * played in a second, long before the next batch was made, so the voice spoke, went quiet and
 * spoke again. The wait ends early when the reply ends or pauses for a tool (flush), or after
 * holdMs, so a short reply is still spoken.
 */
export const FIRST_BATCH_CHARS = 150;

export class SpeechQueue {
  /**
   * @param {(text: string) => Promise<ArrayBuffer|null>} request fetches audio for some text
   * @param {(audio: Promise<ArrayBuffer|null>) => void} onReady receives each request's audio, in order
   * @param {number} maxChars largest text sent in one request
   * @param {{firstChars?: number, holdMs?: number, timer?: {set: Function, cancel: Function}}} options
   */
  constructor(request, onReady, maxChars = 1200, options = {}) {
    this.request = request;
    this.onReady = onReady;
    this.maxChars = maxChars;
    this.firstChars = options.firstChars ?? FIRST_BATCH_CHARS;
    this.holdMs = options.holdMs ?? 1500;
    this.timer = options.timer ?? { set: (fn, ms) => setTimeout(fn, ms), cancel: (id) => clearTimeout(id) };
    this.buffer = [];
    this.inFlight = false;
    this.last = 0;   // length of the batch sent before, 0 when the next one starts a new run
    this.released = false;   // the first batch may leave even though it is short
    this.hold = null;
  }

  push(text) {
    const clean = text.trim();
    if (!clean) return;
    this.buffer.push(clean);
    this.pump();
  }

  /** The reply ended or paused: send what is waiting without waiting for a longer start. */
  flush() {
    this.released = true;
    this.pump();
  }

  /** Barge-in: forget what has not been sent. A request already running finishes on its own. */
  clear() {
    this.buffer.length = 0;
    this.last = 0;
    this.released = false;
    this.stopHold();
  }

  stopHold() {
    if (this.hold !== null) this.timer.cancel(this.hold);
    this.hold = null;
  }

  pump() {
    if (this.inFlight || this.buffer.length === 0) return;
    const starting = this.last === 0;
    if (starting && !this.released && this.buffer.join(' ').length < this.firstChars) {
      if (this.hold === null) {
        this.hold = this.timer.set(() => { this.hold = null; this.flush(); }, this.holdMs);
      }
      return;
    }
    this.stopHold();
    let batch = this.buffer.shift().slice(0, this.maxChars);   // always at least one sentence
    if (starting) {
      while (this.buffer.length && batch.length < this.firstChars
        && batch.length + 1 + this.buffer[0].length <= this.maxChars) {
        batch += ' ' + this.buffer.shift();
      }
    } else {
      const limit = Math.min(this.maxChars, this.last * BATCH_GROWTH);
      while (this.buffer.length && batch.length + 1 + this.buffer[0].length <= limit) {
        batch += ' ' + this.buffer.shift();
      }
    }
    this.last = batch.length;
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
        if (this.buffer.length === 0) {
          this.last = 0;
          this.released = false;
        }
        this.pump();
      });
    this.onReady(audio);
  }
}
