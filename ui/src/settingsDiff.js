// Tells a switch of the HUD's own language apart from a change of the rules the model works
// under. The core makes the same call (SettingsService.SettingsChanged.sameRules): only a rule
// change starts a fresh conversation, so the transcript and the model's context stay in step.

/** True when before and after (both as the core returned them) differ at most in uiLanguage. */
export function sameRules(before, after) {
  if (!before || !after) return false;
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  keys.delete('uiLanguage');
  for (const key of keys) {
    if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) return false;
  }
  return true;
}
