// The core describes each tool call in English (Tool.summarize). In the Arabic interface the HUD
// translates the fixed wording around the arguments; the arguments themselves (paths, links, quoted
// text) are never translated. A summary with no rule is shown as sent rather than broken.

const q = (s) => `«${s}»`;

// [pattern, Arabic builder]. Order matters only where one pattern is a prefix of another.
const AR_RULES = [
  [/^Open in browser: ([\s\S]*)$/, (m) => `فتح في المتصفح: ${m[1]}`],
  [/^Open app: ([\s\S]*)$/, (m) => `فتح برنامج: ${m[1]}`],
  [/^Move to Recycle Bin: ([\s\S]*)$/, (m) => `نقل إلى سلة المحذوفات: ${m[1]}`],
  [/^Create folder ([\s\S]*)$/, (m) => `إنشاء مجلد ${m[1]}`],
  [/^Move ([\s\S]*) → ([\s\S]*)$/, (m) => `نقل ${m[1]} ← ${m[2]}`],
  [/^Read file ([\s\S]*)$/, (m) => `قراءة الملف ${m[1]}`],
  [/^List granted folders$/, () => 'عرض المجلدات المسموح بها'],
  [/^List folder ([\s\S]*)$/, (m) => `عرض المجلد ${m[1]}`],
  [/^Search files named like "([\s\S]*)"$/, (m) => `البحث عن ملفات اسمها يشبه ${q(m[1])}`],
  [/^OVERWRITE ([\s\S]*) \((\d+) characters\)$/, (m) => `الكتابة فوق ${m[1]} (${m[2]} حرفاً)`],
  [/^Append to ([\s\S]*) \((\d+) characters\)$/, (m) => `الإضافة إلى ${m[1]} (${m[2]} حرفاً)`],
  [/^Create ([\s\S]*) \((\d+) characters\)$/, (m) => `إنشاء ${m[1]} (${m[2]} حرفاً)`],
  [/^Read your clipboard \(may contain private text\)$/, () => 'قراءة الحافظة (قد تحتوي على نص خاص)'],
  [/^Copy to clipboard: "([\s\S]*)"$/, (m) => `نسخ إلى الحافظة: ${q(m[1])}`],
  [/^Draft (\S+) message to ([\s\S]*?): "([\s\S]*)"$/, (m) => `مسودة رسالة ${m[1]} إلى ${m[2]}: ${q(m[3])}`],
  [/^Draft (\S+) message: "([\s\S]*)"$/, (m) => `مسودة رسالة ${m[1]}: ${q(m[2])}`],
  [/^Set reminder "([\s\S]*)" for ([\s\S]*)$/, (m) => `ضبط تذكير ${q(m[1])} في ${m[2]}`],
  [/^Cancel reminder ([\s\S]*)$/, (m) => `إلغاء التذكير ${m[1]}`],
  [/^List reminders$/, () => 'عرض التذكيرات'],
  [/^Get the weather for ([\s\S]*)$/, (m) => `معرفة الطقس في ${m[1]}`],
  [/^Read system status$/, () => 'قراءة حالة النظام'],
  [/^List installed apps$/, () => 'عرض البرامج المثبّتة'],
  [/^Search installed apps for "([\s\S]*)"$/, (m) => `البحث في البرامج المثبّتة عن ${q(m[1])}`],
  [/^Search the web for "([\s\S]*)"$/, (m) => `البحث في الويب عن ${q(m[1])}`],
  [/^Read web page ([\s\S]*)$/, (m) => `قراءة صفحة الويب ${m[1]}`],
  [/^Screenshot of all monitors$/, () => 'لقطة لكل الشاشات'],
  [/^Screenshot of the main screen$/, () => 'لقطة للشاشة الرئيسية'],
  [/^Turn on the camera for ONE photo$/, () => 'تشغيل الكاميرا لالتقاط صورة واحدة فقط'],
  [/^Remember: "([\s\S]*)"$/, (m) => `تذكّر: ${q(m[1])}`],
  [/^Forget: "([\s\S]*)"$/, (m) => `نسيان: ${q(m[1])}`],
  [/^Search memory for "([\s\S]*)"$/, (m) => `البحث في الذاكرة عن ${q(m[1])}`],
  [/^Lock the screen$/, () => 'قفل الشاشة'],
  [/^Put the computer to sleep$/, () => 'وضع الجهاز في السكون'],
  [/^RESTART the computer in 60 seconds$/, () => 'إعادة تشغيل الجهاز بعد 60 ثانية'],
  [/^SHUT DOWN the computer in 60 seconds$/, () => 'إطفاء الجهاز بعد 60 ثانية'],
  [/^Cancel a pending restart\/shutdown$/, () => 'إلغاء إعادة التشغيل أو الإطفاء المنتظر'],
  [/^volume up by ~(\d+)%$/, (m) => `رفع الصوت بنحو ${m[1]}%`],
  [/^volume down by ~(\d+)%$/, (m) => `خفض الصوت بنحو ${m[1]}%`],
  [/^mute toggle$/, () => 'كتم الصوت أو إلغاء كتمه'],
  [/^play pause$/, () => 'تشغيل أو إيقاف مؤقت'],
  [/^next track$/, () => 'المقطع التالي'],
  [/^previous track$/, () => 'المقطع السابق'],
];

/** The summary in the interface language; unknown shapes and English pass through unchanged. */
export function localizeSummary(text, lang) {
  if (text == null) return '';
  if (lang !== 'ar') return text;
  for (const [re, build] of AR_RULES) {
    const m = re.exec(text);
    if (m) return build(m);
  }
  return text;
}
