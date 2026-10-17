# Contributing to Haris

Thanks for helping. Haris is a desktop AI assistant that never trusts the model, so every
contribution is judged first by one question: **does it keep the model untrusted?**

**[Arabic below](#المساهمة-بالعربية)**

## Ways to help

- **Try it and report what broke.** Use the *Bug report* form. The version, engine and whether
  Docker and an NVIDIA GPU are present solve most problems quickly.
- **Tell us which model works.** The *Model report* form records whether a local or cloud model
  handles tools well, in English and in Arabic. Those reports feed the
  [local models guide](docs/guides/en/local-models.md).
- **Try to break the permission gate.** Ways around an approval, prompt injection that gets data
  out, files read outside the chosen folders: report these **privately**
  ([SECURITY.md](SECURITY.md)), never in a public issue.
- **Pick an issue** labelled `good first issue`.
- **Improve the Arabic.** Wording, right-to-left layout bugs and dialect support are all welcome.

## Rules that are not up for debate

These keep Haris what it is. A pull request that breaks one will be closed, however useful it is.

1. **No tool runs code.** No shell, exec, script, plugin or "run this command" tool, and no tool that
   starts an arbitrary program.
2. **The core decides, not the model.** A new tool implements `Tool` and declares its `risk`
   (`SAFE`, `CONFIRM`, `CRITICAL`), whether it is `sessionApprovable`, whether it `sendsDataOut`, and
   whether it `readsOutsideContent`. `ToolTrustDeclarationTest` fails until that last decision is
   recorded.
3. **Only a click approves.** Never voice, never a model, and no setting that turns approvals off.
4. **Outside content stays fenced.** Anything a tool reads from outside (files, web, clipboard,
   screenshots, names) is marked as outside content.
5. **Loopback only.** Nothing listens beyond `127.0.0.1`. Nothing needs administrator rights or
   changes the firewall or network settings.

See [THREAT_MODEL.md](THREAT_MODEL.md) for why.

## Set up

Requirements: Windows 10/11, JDK 21, Maven 3.9+, Node 20+. Docker Desktop is optional (voice and
search).

```powershell
.\run.ps1            # build if needed and run from source
```

Details: [Building from source](docs/guides/en/building-from-source.md).

## Before you open a pull request

```powershell
cd ui; npm test; cd ..                                     # HUD tests
mvn test                                                   # core tests
python -m unittest discover -s colab -p "test_*.py" -v     # only if you touched colab/
```

- **Write the test first.** Bug fixes come with a test that failed before the fix.
- **Both languages.** Every HUD string needs an English and an Arabic entry in `ui/src/i18n.js`;
  a test checks that the key sets match. User guides live in `docs/guides/en` and `docs/guides/ar`;
  update both, or say in the PR that the other language still needs it.
- **No new dependency without a reason.** Check [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
  and add the new one there.
- **Commit messages** follow `type(scope): summary`, for example `fix(ui): …`, `feat(voice): …`,
  `docs: …`.
- **Never commit keys or tokens**, not even in tests or screenshots.

## License

Haris is MIT-licensed ([LICENSE](LICENSE)). By contributing you agree that your contribution is
released under the same license.

---

## المساهمة بالعربية

شكراً لمساعدتك. حارس مساعد لا يثق بالنموذج أبداً، لذلك يُحكم على كل مساهمة أولاً بسؤال واحد:
**هل تُبقي النموذج غير موثوق؟**

- **جرّبه وبلّغ عمّا تعطّل** عبر نموذج *Bug report*. معرفة الإصدار والمحرك، وهل يوجد Docker ومعالج NVIDIA،
  تحلّ أغلب المشكلات بسرعة.
- **أخبرنا بالنموذج الذي يعمل** عبر نموذج *Model report*: هل يتعامل مع الأدوات جيداً بالعربية والإنجليزية؟
- **حاول كسر بوابة الموافقة**، لكن بلّغ عن ذلك **سرّاً** ([SECURITY.md](SECURITY.md))، لا في issue عامة.
- **حسّن العربية:** الصياغة، ومشكلات الاتجاه من اليمين لليسار، ودعم اللهجات.

**قواعد ثابتة:**
- لا أداة تشغّل كوداً أو أوامر.
- النواة هي التي تقرّر، لا النموذج.
- لا موافقة إلا بنقرة.
- المحتوى الخارجي يبقى معزولاً.
- لا شيء يستمع خارج `127.0.0.1`، ولا شيء يحتاج صلاحيات المسؤول.

قبل طلب الدمج: شغّل `npm test` داخل `ui` و`mvn test`، وأضف كل نص جديد في الواجهة بالعربية والإنجليزية معاً.
