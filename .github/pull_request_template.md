## What and why

<!-- One or two sentences. Link the issue: Fixes #… -->

## How it was tested

- [ ] `cd ui; npm test`
- [ ] `mvn test`
- [ ] Colab tests (only if `colab/` changed)
- [ ] Tried it in the running app

## Checklist

- [ ] No tool runs code, a shell command or an arbitrary program.
- [ ] A new or changed tool declares its risk, `sessionApprovable`, `sendsDataOut` and `readsOutsideContent`.
- [ ] Approvals still come only from a click; nothing listens beyond `127.0.0.1`.
- [ ] New HUD text has English and Arabic entries in `ui/src/i18n.js`.
- [ ] Guides updated (English and Arabic, or noted below).
- [ ] No keys, tokens or personal data in code, tests or screenshots.
