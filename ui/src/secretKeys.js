// The keys Settings can store, and removing one. The window only ever learns whether a key is
// stored (true/false from the core), never its value; removing one asks the user first.

/** Each stored key: its vault name, the hint showing its state, its Remove button and its hint texts. */
export const KEY_SLOTS = [
  { name: 'anthropic', state: 'anthropic-key-state', remove: 'anthropic-key-remove', saved: 'settings.keyStored', missing: 'settings.keyMissing' },
  { name: 'openai-compatible', state: 'openai-key-state', remove: 'openai-key-remove', saved: 'settings.keyStored', missing: 'settings.keyMissing' },
  { name: 'colab', state: 'colab-key-state', remove: 'colab-key-remove', saved: 'settings.colabTokenSaved', missing: 'settings.colabTokenMissing', warnWhenMissing: true },
  { name: 'gemini', state: 'gemini-key-state', remove: 'gemini-key-remove', saved: 'settings.geminiKeySaved', missing: 'settings.geminiKeyMissing' },
];

/**
 * Asks for confirmation, then deletes the stored key. Resolves to the core's new settings view,
 * or null when the user said no. Only the four names above are ever sent.
 */
export async function removeKey(name, { confirm, api, t }) {
  if (!KEY_SLOTS.some((slot) => slot.name === name)) throw new Error(`Unknown key: ${name}`);
  const key = t(`settings.keyName.${name}`);
  if (!confirm(t('settings.removeKeyConfirm', { key }))) return null;
  return api(`/api/secrets/${encodeURIComponent(name)}`, { method: 'DELETE' });
}
