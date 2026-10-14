// When the daily briefing goes out. It is an ordinary chat turn, and send() silences Haris and
// refuses to start while another turn runs, so it must wait for a quiet moment: never over the
// status report being spoken, never into the user's own turn. And it is claimed (marked done for
// today) only at the moment it can start, so a briefing that had to wait is never lost.
// Pure: the page passes in what "idle" means, the claim, send and sleep.

/** Resolves once isIdle() has been true for quietMs() in a row, checked every stepMs. */
export async function whenIdle(isIdle, { sleep, stepMs = 250, quietMs = () => 2000 }) {
  let quiet = 0;
  for (;;) {
    if (!isIdle()) quiet = 0;
    else if (quiet >= quietMs()) return;
    else quiet += stepMs;
    await sleep(stepMs);
  }
}

/**
 * Waits for a quiet moment, then claims today's briefing and, if one is due, sends it. If the user
 * started something while the claim was on its way, it waits for that to finish first (the claim
 * is already spent, so the briefing must still go out). Returns true when it was sent.
 */
export async function deliverBriefing({ claim, send, isIdle, sleep, quietMs, stepMs }) {
  await whenIdle(isIdle, { sleep, quietMs, stepMs });
  const briefing = await claim().catch(() => null);
  if (!briefing?.prompt) return false;
  while (!isIdle()) await whenIdle(isIdle, { sleep, quietMs, stepMs });
  send(briefing.prompt);   // same tick as the idle check: nothing can start in between
  return true;
}

/** The quiet a briefing needs: a short pause after launch, a real lull once the user is talking to Haris. */
export function briefingQuietMs(userHasSpoken) {
  return userHasSpoken ? 45_000 : 2_000;
}
