<div dir="rtl">

# حل المشكلات

**[English](../en/troubleshooting.md)** · [كل الأدلة](README.md)

المشكلات مرتبة حسب ما تراه. النصوص *المائلة* هي الرسائل الحرفية التي يعرضها حارس. بعض الرسائل تأتي من النواة
بالإنجليزية دائماً حتى لو كانت الواجهة بالعربية، فتركناها كما هي.

## أين تبحث أولاً

| المكان | ما يخبرك به |
|---|---|
| لوحة **الصوت** | هل تحويل الكلام إلى نص (**السمع**) والنطق (**النطق**) وكلمة التنبيه تعمل، وأين تعمل. |
| لوحة **الأمان** | هل لهذه النافذة جلسة صالحة (**الرمز موثَّق**). |
| لوحة **النظام** | أي محرك مستخدم. |
| `~\.haris\logs\haris.log` | سجل البرنامج: التشغيل، وDocker، والمحركات، والأخطاء. لا يحتوي أبداً نص محادثة أو محتوى ملفات أو مفاتيح. حتى 2 MB للملف، والملفات الأقدم بجواره. |
| `~\.haris\audit.jsonl` | كل استدعاء أداة ونتيجته. |
| `docker logs haris-voice-whisper-1` | سجل Whisper نفسه (وأيضاً `haris-voice-piper-1` و`haris-search-searxng-1`). |

الرمز `~` هو مجلد المستخدم: افتح `%USERPROFILE%\.haris\logs` في مستكشف الملفات.

## حارس لا يفتح

| العَرَض | السبب والحل |
|---|---|
| المثبّت يقول *"Haris (or the older JARVIS) is running. Close its window first, then run the installer again."* | أغلق نافذة حارس، وانتظر نحو 20 ثانية حتى تتوقف النواة، ثم شغّل `install.ps1` مرة أخرى. |
| المثبّت يقول *"Install incomplete: … is missing or truncated."* | كان هناك ملف قيد الاستخدام. أغلق حارس وأي نافذة له، ثم شغّل `install.ps1` مرة أخرى. |
| لا يظهر شيء بعد تشغيل حارس | حارس يحتاج Edge أو Chrome في مجلدات تثبيتهما المعتادة. السجل حينها يقول *"Neither Edge nor Chrome found. Open this address in a browser on this machine:"* ثم العنوان. ثبّت Edge أو Chrome، أو افتح ذلك العنوان بالضبط (ومعه الجزء `#token=…`) في متصفح على هذا الجهاز. |
| ‏PowerShell يرفض تشغيل `install.ps1` | شغّل `powershell -ExecutionPolicy Bypass -File install.ps1`. |

## «هذه النافذة بلا رمز جلسة»

*«هذه النافذة بلا رمز جلسة. شغّل حارس من اختصاره ليفتح نافذته الخاصة.»* ولوحة الأمان تعرض **غير مصرَّح**.

الواجهة لا تعمل إلا في النافذة التي يفتحها حارس بنفسه، لأنها وحدها تستلم رمز الجلسة الخاص بهذا التشغيل. تظهر
الرسالة إذا فتحت العنوان المحلي بنفسك (من المفضلة أو السجل أو رابط منسوخ بدون الرمز)، أو إذا كان العنوان من
تشغيل سابق. أغلق النافذة وشغّل حارس من قائمة ابدأ. الرمز يتغير في كل تشغيل، فالعنوان القديم لا يعمل مرة أخرى أبداً.

## تغيّر المنفذ، أو طُلب إذن الميكروفون من جديد

يختار حارس مرة واحدة منفذاً عشوائياً متاحاً بين 41000 و59000 ويحفظه في `~\.haris\port`، حتى يتذكر المتصفح إذن
الميكروفون. إذا كان هذا المنفذ مشغولاً ببرنامج آخر عند التشغيل، يختار حارس منفذاً جديداً ويطلب المتصفح إذن
الميكروفون من جديد؛ وهذا متوقع.

حارس لا يمنع تشغيل نسخة ثانية منه. إذا شغّلته مرتين، تأخذ النواة الثانية منفذاً آخر ونافذة خاصة بها. أغلق
الاثنتين وشغّل حارس مرة واحدة.

## Docker لا يعمل

تسمع أو ترى *«محركا الصوت متوقفان — هل Docker Desktop يعمل؟»* في تقرير الحالة، أو *«لم أستطع التحدث: محرك
النطق لم يستجب. تأكد أن Docker Desktop يعمل — لوحة الصوت تعرض حالة كل محرك.»*، أو يفشل البحث في الويب. والسجل
يقول *"Docker is not running: voice and web search are off until it is started."*

1. شغّل Docker Desktop وانتظر حتى يقول إنه يعمل.
2. أغلق حارس وشغّله مرة أخرى. حارس ينتظر Docker نحو دقيقتين عند التشغيل، ولا يشغّل المحركات بعد ذلك من تلقاء
   نفسه.

قد تقترح رسالة خطأ البحث أن تشغّل `docker compose -f search/compose.yaml up -d` بنفسك. هذا الأمر يحتاج سر SearXNG
الذي يمرره حارس من Credential Manager، لذلك إعادة تشغيل حارس هي الطريقة المضمونة.

## محركات الصوت تبقى متوقفة

| العَرَض | السبب والحل |
|---|---|
| **○ متوقف** لعدة دقائق بعد أول تشغيل على الإطلاق | ‏Docker ينزّل الصور ونموذج الكلام. انتظر؛ والأمر `docker ps` يعرض الحاويات عندما تعمل. |
| *"The speech-to-text engine is not running. Start it with: docker compose -f voice/compose.yaml up -d"* | ‏Whisper لم يرد. راجع لوحة الصوت، وابحث في السجل عن *"docker compose up -d failed for …"*. إعادة تشغيل حارس تحل المشكلة عادة. |
| ‏Whisper يتوقف باستمرار بعد أن أغلقت حارس أثناء تنزيل النموذج لأول مرة | يكتشف حارس النموذج نصف المكتوب (*"Unable to open file 'model.bin'"*)، فيحذف المجلد وينزّل من جديد تلقائياً، مرة واحدة. إذا استمرت المشكلة، أغلق حارس وشغّل `docker volume rm haris-voice_whisper-data`، ثم شغّل حارس. |
| ‏`WHISPER_OFFLINE=true` مع `WHISPER_MODEL` جديد | الوضع غير المتصل يمنع تنزيل النموذج الجديد. اجعل `WHISPER_OFFLINE=false` لتشغيل واحد. |
| *"Still working on the previous recording. Say it again in a moment."* | حارس يحوّل تسجيلاً واحداً في كل مرة. انتظر الأول. |
| حارس يتجاهل ما قلته | التسجيلات الهادئة جداً تُستبعد قبل التحويل. اقترب من الميكروفون، واجعل **أتحدث** لغتك بدلاً من **اكتشاف تلقائي**. |

## الميكروفون محجوب

*«تم رفض الوصول للميكروفون، لذلك كلمة التنبيه متوقفة.»* أو يفشل الإدخال الصوتي فوراً.

1. في ويندوز: **Settings → Privacy & security → Microphone**، اسمح بالوصول للميكروفون، بما في ذلك تطبيقات سطح
   المكتب.
2. اسمح بالميكروفون لنافذة حارس من أذونات الموقع في المتصفح.
3. إذا كنت ضغطت *Block* من قبل، أغلق حارس واحذف المجلد `~\.haris\hud-profile`. هذا ملف تعريف المتصفح الخاص
   بالواجهة، والتشغيل التالي سيطلب الإذن من جديد. (وهذا ينسى أيضاً اللوحة المثبّتة واختيار إخفاء المحادثة.)

## حارس لا ينطق

| العَرَض | السبب والحل |
|---|---|
| *«المتصفح يمنع الصوت: انقر في أي مكان بهذه النافذة مرة واحدة لتفعيله.»* | انقر مرة واحدة في أي مكان داخل نافذة حارس. |
| *«لم أستطع التحدث: محرك النطق لم يستجب…»* | ‏Piper لا يعمل (Docker)، أو فشل Gemini وPiper ليس متاحاً كبديل. راجع صف **النطق**. |
| لا صوت أبداً ولا رسالة خطأ | تأكد من **انطق الردود بصوت** في الإعدادات وجرّب **▶ اختبار السماعة**. |

## كلمة التنبيه لا تفعل شيئاً

- **استمع لـ "Hey Haris"** مغلقة افتراضياً؛ فعّلها من الإعدادات.
- قل تحية يليها الاسم مباشرة: "Hey Haris" أو "Okay Haris" أو "Ya Haris". الاسم وحده يُتجاهل.
- حارس لا يستمع لكلمة التنبيه وهو يتكلم. استخدم Ctrl+Space لمقاطعته.
- **○ نموذج "hey haris" غير موجود** في لوحة الصوت تعني أن نموذج كلمة التنبيه غير موجود في مجلد `models`. أعد
  التثبيت، أو عند التشغيل من الكود شغّل `models\fetch-models.ps1`.

## كارت الشاشة لا يُستخدم

لوحة الصوت تعرض **● يعمل** بدلاً من **● يعمل (GPU)**. ابحث في السجل عن أحد هذه السطور:

| سطر السجل | المعنى والحل |
|---|---|
| *"Speech-to-text device: cpu (HARIS_WHISPER_GPU is off)"* | أنت أوقفت الكارت بـ `HARIS_WHISPER_GPU`. احذف هذا الإعداد. |
| *"Speech-to-text device: cpu"* بدون سبب | ‏Docker لا يعرض بيئة `nvidia`، أو `nvidia-smi -L` لا يعرض كارتاً. حدّث تعريف NVIDIA وفعّل دعم الكارت في Docker Desktop. |
| *"Docker Compose … cannot apply compose.gpu.yaml (2.24.4 or later is needed); speech-to-text uses the CPU."* | حدّث Docker Desktop. |
| *"Downloading the GPU speech-to-text image … in the background (several GB, one time)..."* | أول مرة: حارس يستخدم المعالج الآن، والكارت من التشغيل التالي. التقدم يظهر في السجل، والتنزيل المنقطع يُستكمل في المرة القادمة. |
| *"Speech-to-text did not start on the GPU recently; using the CPU for now (delete … to try the GPU again)."* | فشلت حاوية الكارت خلال آخر ثلاثة أيام. أصلح السبب، ثم احذف `~\.haris\whisper-gpu-failed` وأعد تشغيل حارس. |

بعد ذلك راجع `docker logs haris-voice-whisper-1` لترى خطأ حاوية الكارت نفسها. راجع
[الصوت](voice.md#التعرف-على-الكلام-بكارت-nvidia).

## صوت Gemini يتوقف

- **● Piper (حد Gemini، يعود HH:MM)**: هذا حد الاستخدام من جوجل، وغالباً الحصة اليومية المجانية. ينطق Piper حتى
  ذلك الوقت ثم يعود Gemini. لا تحتاج لفعل شيء، أو استخدم مفتاحاً بحصة أكبر.
- السجل يقول *"Gemini speech failed, using the local voice: Gemini speech answered HTTP 4xx"*: مع 400 أو 401 أو
  403 راجع المفتاح في **الإعدادات ← الصوت ← مفتاح Google AI Studio**. وبعد أي فشل ينطق Piper لمدة دقيقة قبل
  تجربة Gemini مرة أخرى.

## انتهت جلسة Colab

| الرسالة | الحل |
|---|---|
| *"Colab is not answering (HTTP 502…). If it runs in Colab, the session may have ended: run the notebook again."* | انتهت جلسة Colab (بعد نحو 90 دقيقة خمول، وبحد أقصى نحو 12 ساعة). شغّل **Run all** في الدفتر مرة أخرى. |
| *"Colab stopped answering for 120 s. The Colab session may have ended: run the notebook again."* | نفس الحل. |
| *"Colab rejected the API key or access token. Check it in Settings."* | الرمز تغيّر: بدون سر `HARIS_TOKEN` تطبع كل جلسة رمزاً جديداً. الصقه، أو احفظه في Colab باسم `HARIS_TOKEN`. |
| *"Colab is not reachable at …. Is it running?"* | بدون نطاق ngrok يتغير عنوان Cloudflare في كل جلسة. الصق **عنوان Colab** الجديد. |
| *"Colab refused the request (HTTP 403)."* | نفق يصل إلى Ollama مباشرة (بدون الدفتر) يرسل اسم مضيف يرفضه Ollama. استخدم دفتر حارس. |
| *"No Colab address yet. Run the notebook and paste its https:// link in Settings."* | املأ **عنوان Colab**. |

إذا فشل التعرف على الكلام على Colab، يستخدم حارس Whisper المحلي لمدة دقيقة ثم يجرّب Colab مرة أخرى.

## قائمة النماذج فارغة

*«لم يتم العثور على نماذج. هل المحرك يعمل؟»* بعد **حفظ واكتشاف النماذج**.

| المحرك | تأكد من |
|---|---|
| ‏Ollama | أن Ollama يعمل وأنك نزّلت نموذجاً (`ollama list`)، وأن العنوان `http://127.0.0.1:11434`. |
| ‏LM Studio | أن الخادم المحلي يعمل في LM Studio وأن هناك نموذجاً محمّلاً، وأن العنوان `http://127.0.0.1:1234`. |
| متوافق مع OpenAI | أن العنوان الأساسي صحيح وأن مفتاح API محفوظ. بعض المزوّدين لا يعرضون قائمة نماذج: اكتب اسم النموذج بنفسك. |
| ‏Colab | أن الدفتر يعمل، وأن العنوان والرمز هما الحاليان. |

يمكنك دائماً كتابة اسم النموذج في خانة **النموذج** بنفسك.

## أخطاء داخل المحادثة

هذه الرسائل تأتي من النواة بالإنجليزية:

| الرسالة | المعنى |
|---|---|
| *"Ollama is not reachable at http://127.0.0.1:11434. Is it running?"* | شغّل Ollama (أو LM Studio). |
| *"No model selected for Ollama. Pick one in Settings."* | اختر نموذجاً. |
| *"Ollama does not know the model "…"."* | الاسم خطأ أو النموذج غير منزّل. |
| *"No Claude API key saved. Add it in Settings."* · *"Claude rejected the API key. Check it in Settings."* | الصق مفتاحاً صالحاً. |
| *"Claude rate limit reached. Try again in a moment."* | انتظر قليلاً. |
| *"Could not reach Claude. Check your internet connection."* | مشكلة في الشبكة. |
| *"No API key saved for the cloud provider. Add it in Settings."* | المحرك المتوافق مع OpenAI يحتاج مفتاحاً. |
| *"This model does not support tools, so I can only talk…"* | اختر نموذجاً يدعم الأدوات ([المحركات](engines.md#اختيار-النموذج)). |
| *"Still working on the previous message."* | طلب واحد في كل مرة؛ انتظر الرد. |
| *"The core is not responding."* | النواة توقفت. أغلق النافذة وشغّل حارس من جديد. |
| *«حدث خطأ في النواة. التفاصيل في ملف السجل: %USERPROFILE%\\.haris\\logs\\haris.log»* | خطأ غير متوقع في النواة. افتح ذلك الملف (الصق المسار في مستكشف الملفات)؛ آخر سطوره تذكر الخطأ. نفس المسار يظهر أسفل نافذة الإعدادات. |
| *"I stopped after 12 steps to stay on the safe side. Ask me to continue if needed."* | الطلب احتاج خطوات أدوات كثيرة. قل «كمّل». |
| *"This conversation got very long, so I started a fresh one."* | يحدث تلقائياً بعد محادثة طويلة جداً. |

## الأدوات ترفض

| الرسالة | الحل |
|---|---|
| *"No folders are granted. Add folders in Settings → Allowed folders."* | أضف مجلداً من **الإعدادات ← الصلاحيات**. |
| *"Outside the folders you granted: …"* | أضف ذلك المجلد، أو انقل الملف إلى مجلد مسموح. |
| *"This file looks like it holds secrets, so it is off-limits."* · *"This location holds credentials and is off-limits."* | هذا مقصود. |
| *"Only text files can be written (…)."* | حارس يكتب الأنواع النصية فقط. |
| *"No installed app is called "…". Use list_apps to see what is installed."* | استخدم الاسم كما يظهر في قائمة ابدأ. |
| *"The local search engine is not running…"* | راجع [Docker لا يعمل](#docker-لا-يعمل). |
| *«تعذّر التقاط صورة الكاميرا.»* · *"The camera did not respond (is the HUD open and the camera allowed?)."* | اسمح بالكاميرا لنافذة حارس، وتأكد أن برنامجاً آخر لا يستخدمها. |
| *"The memory file was modified outside Haris or the key changed; refusing to read it."* | الملف `memory.enc` لم يعد يطابق المفتاح في Credential Manager (مثلاً بعد حذف `Haris\|memory-key`). المعلومات القديمة لا يمكن قراءتها. لتبدأ ذاكرة جديدة، أغلق حارس وانقل `~\.haris\memory.enc` إلى مكان آخر. |

## التذكيرات لم تظهر

التذكيرات تعمل فقط طالما حارس يعمل. التذكير الذي فات بأقل من ساعة يُعرض في التشغيل التالي، والأقدم منه يُحذف.

## تعذر حفظ الإعدادات

الرسالة الحمراء أسفل النافذة تحدد المشكلة:

| الرسالة | الحل |
|---|---|
| *"Ollama must run on this machine (127.0.0.1 or localhost)"* (ومثلها لـ LM Studio) | المحركات المحلية لا بد أن تستخدم عنواناً محلياً. للخادم البعيد استخدم **سحابة متوافقة مع OpenAI**. |
| *"OpenAI-compatible endpoint must use https://"* | استخدم عنوان HTTPS. |
| *"The Colab address must start with https:// (copy it from the notebook)."* | انسخ العنوان كاملاً. |
| *"That is a local address; for a local Ollama choose the Ollama provider."* | استخدم **Ollama (محلي)**. |
| *"Folder does not exist: …"* | راجع المسار؛ مجلد واحد في كل سطر. |
| *"A whole drive cannot be granted: …"* · *"The whole home folder cannot be granted; pick folders inside it."* · *"This folder is off-limits for safety: …"* | اختر مجلداً أضيق. |
| *"At most 10 folders can be granted."* | احذف بعضها. |
| *"Voice names look like en_GB-alan-medium."* | صحّح اسم صوت Piper. |

## خانة التشغيل مع ويندوز معطلة

*«متاح في النسخة المثبّتة فقط، وليس عند التشغيل من الكود.»* استخدم البرنامج المثبّت.

## إعادة ضبط الإعدادات

1. أغلق حارس.
2. احذف (أو أعد تسمية) `~\.haris\settings.json`.
3. شغّل حارس. سيستخدم القيم الافتراضية ويطلب منك اختيار محرك.

مفاتيحك وذاكرتك وتذكيراتك وسجل التدقيق لا تتأثر. وإذا تعذرت قراءة `settings.json` أصلاً، يعود حارس للقيم
الافتراضية من تلقاء نفسه (والسجل يقول *"settings.json unreadable or unsafe … using defaults"*).

| لإعادة ضبط أيضاً | احذف (وحارس مغلق) |
|---|---|
| أذونات النافذة، واللوحة المثبّتة، وإخفاء المحادثة | `~\.haris\hud-profile` |
| تقرير حالة اليوم (لتسمعه مرة أخرى) | `~\.haris\last-greeting` |
| كل شيء | شغّل `uninstall.ps1 -RemoveData` ثم ثبّت من جديد. |

</div>
