<div dir="rtl">

# البناء من الكود

**[English](../en/building-from-source.md)** · [كل الأدلة](README.md)

للمطوّرين الذين يريدون تشغيل حارس من الكود، أو بناء ملف التثبيت، أو تشغيل الاختبارات، أو المساهمة.

## المتطلبات

| الأداة | الإصدار | الاستخدام |
|---|---|---|
| ويندوز | 10 أو 11 | حارس يستخدم واجهات ويندوز، ويُبنى ويُختبر عليه. |
| ‏JDK | 21 | النواة. السكربتان `run.ps1` و`package.ps1` يستخدمان `C:\Program Files\Java\jdk-21` إن وُجد، وإلا `JAVA_HOME`. والسكربت `package.ps1` يحتاج `jpackage` من الـ JDK. |
| ‏Maven | 3.9 أو أحدث | بناء النواة واختبارها. |
| ‏Node.js (مع npm) | 20 أو أحدث | بناء الواجهة واختبارها. |
| ‏Edge أو Chrome | — | نافذة الواجهة. |
| ‏Docker Desktop | اختياري | الصوت والبحث في الويب. |
| ‏Python 3 | اختياري | اختبارات بروكسي Colab وإعادة توليد الدفتر. |

السكربتان ينزّلان نموذج كلمة التنبيه مرة واحدة (`vosk-model-small-en-us-0.15`، نحو 40 MB) عبر
`models\fetch-models.ps1`، والذي يتحقق منه ببصمة SHA-256 ثابتة قبل استخدامه.

## التشغيل من الكود

<div dir="ltr">

```powershell
git clone https://github.com/MahmoudFarouk2479/haris.git
cd haris
.\run.ps1
```

</div>

ما يفعله `run.ps1`:

1. ينزّل نموذج كلمة التنبيه إن لم يكن موجوداً؛
2. يعيد البناء إذا كان أي ملف في `src` أو `ui\src` أو `ui\public` أو `ui\index.html` أو `pom.xml` أحدث من ملف
   الـ jar: ‏`npm ci` (أول مرة)، ثم `npm run build` داخل `ui`، ثم `mvn -q -B package -DskipTests`؛
3. ينبّهك إذا لم يكن Docker مثبّتاً؛
4. يشغّل `java -jar target\haris-core-<version>.jar`.

الأمر `.\run.ps1 -Rebuild` يفرض إعادة بناء كاملة.

التشغيل من الكود يستخدم نفس مجلد البيانات الذي يستخدمه البرنامج المثبّت، أي `~\.haris` (الإعدادات، والذاكرة،
والمفاتيح، وسجل التدقيق). وملفات Docker Compose يبحث عنها في المجلد الحالي (`voice\` و`search\`)، ولهذا يعمل
`run.ps1` من جذر المشروع. وخيار **التشغيل مع ويندوز** متاح في البرنامج المثبّت فقط.

### خصائص مفيدة

النواة تقرأ خصائص Spring هذه (من `src/main/resources/application.properties`)، ويمكنك تغييرها من سطر الأوامر،
مثلاً `java -jar target\haris-core-0.6.0.jar --haris.open-window=false`:

| الخاصية | الافتراضي | التأثير |
|---|---|---|
| `haris.open-window` | `true` | ‏`false`: لا تُفتح نافذة الواجهة؛ والسجل يطبع العنوان (ومعه الرمز) لتفتحه بنفسك. |
| `haris.exit-on-window-close` | `true` | ‏`false`: تبقى النواة تعمل بعد إغلاق النافذة. |
| `haris.engines.autostart` | `true` | ‏`false`: لا تُشغَّل محركات الصوت والبحث في Docker. |
| `haris.engines.stop-on-exit` | `true` | ‏`false`: تبقى الحاويات تعمل بعد خروج حارس. |
| `haris.voice.stt-port` / `haris.voice.tts-port` | `10300` / `10200` | منفذا Whisper وPiper. |
| `haris.search.port` | `10500` | منفذ SearXNG. |
| `server.port` | `0` | ‏`0` يعني المنفذ العشوائي الثابت المحفوظ في `~\.haris\port`؛ وأي قيمة أخرى تُستخدم كما هي. العنوان دائماً `127.0.0.1`. |

## بناء ملف التثبيت

<div dir="ltr">

```powershell
.\package.ps1            # with tests
.\package.ps1 -SkipTests # faster
```

</div>

ما يفعله `package.ps1`:

1. ينزّل نموذج كلمة التنبيه؛
2. يشغّل اختبارات الواجهة (`npm test`) ويبني الواجهة؛
3. يبني النواة بـ Maven (ويشغّل اختبارات Java إلا مع `-SkipTests`)؛
4. ينشئ برنامجاً مستقلاً بـ `jpackage` (معه بيئة Java الخاصة به، و`-Xmx768m`)، ويضيف مجلدات `voice` و`search`
   و`models` بجوار `Haris.exe`؛
5. يحذف أي ملف `.env` من الناتج، حتى لا تُشحن أسرار أو إعدادات شخصية أبداً؛
6. يكتب `target\Haris-<version>-windows.zip` الذي يحتوي `Haris\` و`install.ps1` و`uninstall.ps1` و`haris_colab.ipynb`.

مجلد البرنامج وحده موجود في `target\dist\Haris\`.

## الاختبارات

<div dir="ltr">

```powershell
cd ui; npm test; cd ..        # HUD: Node's built-in test runner (node --test "src/**/*.test.js")
mvn test                      # core: JUnit 5 and AssertJ
python -m unittest discover -s colab -p "test_*.py" -v   # Colab proxy
```

</div>

| المجموعة | تغطي |
|---|---|
| `ui/src/*.test.js` | الترجمات (اللغتان لهما نفس المفاتيح، وكل مفتاح مستخدم موجود)، واللوحات وعمود المحادثة، والشعار، وتقرير الحالة والأسماء الأولى، وتوقيت الملخص اليومي، وتجميع جمل النطق، والنص ثنائي الاتجاه، وفروق الإعدادات، وبنية الصفحة. |
| `src/test/java/...` | بوابة الصلاحيات، وملخصات التدقيق، وحارس المسارات، والتحقق من الإعدادات، وفلتر الوصول المحلي، وقواعد SSRF، والذاكرة المشفّرة، ومعالجة الصور، وبروتوكول Wyoming للصوت، وصوت Colab وGemini، والاتصالات المتجمدة، وتوجيه Whisper بين الكارت والمعالج، ومطابقة كلمة التنبيه، ونقل البيانات القديمة، واختبار يُلزم كل أداة بأن تعلن هل مخرجاتها محتوى خارجي. |
| `colab/test_haris_proxy.py` | تعريف العملاء والمنع المؤقت في بروكسي Colab. |

لقياس دقة كلمة التنبيه على تسجيلاتك، اضبط `HARIS_WAKE_CLIPS` على مجلد فيه ملفات `.pcm` (16 kHz، ‏16-bit، أحادية)
أسماؤها تبدأ بـ `pos` (يجب أن توقظ) و`neg` (يجب ألا توقظ)، قبل تشغيل `mvn test`.

## تنظيم المشروع

<div dir="ltr">

```
src/main/java/dev/haris/
  HarisApplication.java  entry point; LegacyData moves ~/.jarvis to ~/.haris once
  agent/      AgentService (model loop + permission gate), ConfirmationBroker, AuditLog,
              UndoStack, ConversationService (system prompt), EventHub
  chat/       ChatController (/api/chat, server-sent events)
  llm/        ChatProvider, AnthropicProvider, OpenAiCompatibleProvider, ProviderFactory
  tools/      Tool, Risk, PathGuard, ToolRegistry, impl/ (the tools)
  memory/     MemoryStore (AES-256-GCM), remember / recall / forget
  vision/     screen and camera tools, Images (scale + strip metadata)
  web/        WebSearchTool, FetchPageTool, PageFetcher (pinned DNS), NetGuard (SSRF rules)
  voice/      WakeWordDetector (Vosk), VoiceEngines (Wyoming), GeminiSpeech, ColabSpeech
  security/   LocalAccessFilter, SessionToken, SecretStore (Credential Manager)
  settings/   Settings, SettingsService (validation)
  system/     EnginesService (Docker), WhisperRoute (GPU/CPU), briefing, greeting,
              autostart, weather, system stats
  launcher/   AppWindowLauncher (Edge/Chrome app window), StablePort, HudWindow
ui/           Vite HUD: index.html, src/ (main.js, i18n.js, drawers, emblem, voice …), public/
voice/        compose.yaml (Whisper + Piper), compose.gpu.yaml (GPU Whisper), .env.example
search/       compose.yaml and settings.yml for SearXNG
colab/        haris_proxy.py, build_notebook.py, haris_colab.ipynb, test_haris_proxy.py
models/       fetch-models.ps1 (the model is downloaded, not committed)
packaging/    icon, install.ps1, uninstall.ps1
docs/         media (screenshots, GIFs), guides, design notes
```

</div>

## كيف تدخل الواجهة في ملف الـ jar

1. الملف `ui/vite.config.js` يبني الواجهة مباشرة داخل `src/main/resources/static` (بعد تفريغه). لا تُضمَّن الملفات
   كعناوين `data:`، ولا توجد source maps، وهذا يُبقي سياسة أمان المحتوى (CSP) صارمة.
2. ‏Maven يضع هذا المجلد داخل ملف الـ jar، وSpring Boot يقدّمه من `127.0.0.1`.
3. ‏`LocalAccessFilter` لا يسمح بدون رمز الجلسة إلا بـ `/` و`/index.html` و`/mic-worklet.js` و`/assets/*`؛ وكل
   ما عدا ذلك يحتاج الرمز.

المجلد `src/main/resources/static` موجود في `.gitignore`. إذا شغّلت `mvn package` بدون بناء الواجهة أولاً، سيخرج
ملف jar بلا واجهة: استخدم `run.ps1` أو `package.ps1`، أو شغّل `npm run build` داخل `ui` أولاً.

الواجهة مكتوبة بوحدات ES عادية بدون أي إطار عمل. نصوصها موجودة في `ui/src/i18n.js` (بالإنجليزية والعربية)، أما
ردود النموذج فلا تُترجم هناك أبداً.

## دفتر Colab

الملف `colab/haris_colab.ipynb` مولَّد آلياً: للبروكسي مصدر واحد هو `colab/haris_proxy.py`. بعد تعديل البروكسي أو
نص الدفتر في `colab/build_notebook.py`، أعد توليده بالأمر `python colab/build_notebook.py`.

## المساهمة

- **بلاغات الأمان** تكون عبر بلاغ خاص، وليس issue عاماً أبداً: راجع [SECURITY.md](../../../SECURITY.md).
- **أبقِ النموذج غير موثوق.** لا يجوز لأي أداة أن تشغّل كوداً أو أمر shell أو برنامجاً عشوائياً. الأداة الجديدة
  تطبّق الواجهة `Tool` وتحدد `risk`، وهل هي `sessionApprovable`، وهل `sendsDataOut`، وهل `readsOutsideContent`؛
  والاختبار `ToolTrustDeclarationTest` يفشل حتى يُسجَّل القرار الأخير.
- **اللغتان.** كل نص في الواجهة يحتاج مدخلاً بالإنجليزية وآخر بالعربية في `ui/src/i18n.js`، والاختبارات تتأكد
  أن مجموعتي المفاتيح متطابقتان.
- **شغّل كل الاختبارات** (`npm test` و`mvn test`، واختبارات Colab إذا عدّلت في `colab/`) قبل إرسال التغيير.
- **رسائل الـ commit** في هذا المستودع على شكل `type(scope): summary`، مثلاً `fix(ui): …` و`feat(voice): …`
  و`docs: …`.
- حارس مرخّص بـ MIT ([LICENSE](../../../LICENSE)). راجع [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md)
  قبل إضافة أي مكتبة.

</div>
