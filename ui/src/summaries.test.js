import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { localizeSummary } from './summaries.js';

// One sample per summary shape the core builds (src/main/java/dev/haris/**/…Tool.summarize).
const SAMPLES = [
  ['Open in browser: https://example.com/a', 'فتح في المتصفح: https://example.com/a'],
  ['Open app: Notepad', 'فتح برنامج: Notepad'],
  ['Move to Recycle Bin: C:\\Users\\me\\Desktop\\a.txt', 'نقل إلى سلة المحذوفات: C:\\Users\\me\\Desktop\\a.txt'],
  ['Create folder C:\\Users\\me\\Desktop\\new', 'إنشاء مجلد C:\\Users\\me\\Desktop\\new'],
  ['Move C:\\a.txt → C:\\b.txt', 'نقل C:\\a.txt ← C:\\b.txt'],
  ['Read file C:\\notes.txt', 'قراءة الملف C:\\notes.txt'],
  ['List granted folders', 'عرض المجلدات المسموح بها'],
  ['List folder C:\\Users\\me\\Documents', 'عرض المجلد C:\\Users\\me\\Documents'],
  ['Search files named like "report"', 'البحث عن ملفات اسمها يشبه «report»'],
  ['Create C:\\plan.txt (42 characters)', 'إنشاء C:\\plan.txt (42 حرفاً)'],
  ['OVERWRITE C:\\plan.txt (42 characters)', 'الكتابة فوق C:\\plan.txt (42 حرفاً)'],
  ['Append to C:\\plan.txt (42 characters)', 'الإضافة إلى C:\\plan.txt (42 حرفاً)'],
  ['Read your clipboard (may contain private text)', 'قراءة الحافظة (قد تحتوي على نص خاص)'],
  ['Copy to clipboard: "hello"', 'نسخ إلى الحافظة: «hello»'],
  ['Draft whatsapp message to Ali: "see you"', 'مسودة رسالة whatsapp إلى Ali: «see you»'],
  ['Draft email message: "hi"', 'مسودة رسالة email: «hi»'],
  ['Set reminder "call mom" for Tue 20 Oct 18:00', 'ضبط تذكير «call mom» في Tue 20 Oct 18:00'],
  ['Cancel reminder 3', 'إلغاء التذكير 3'],
  ['List reminders', 'عرض التذكيرات'],
  ['Get the weather for Cairo', 'معرفة الطقس في Cairo'],
  ['Read system status', 'قراءة حالة النظام'],
  ['List installed apps', 'عرض البرامج المثبّتة'],
  ['Search installed apps for "word"', 'البحث في البرامج المثبّتة عن «word»'],
  ['Search the web for "owasp agentic"', 'البحث في الويب عن «owasp agentic»'],
  ['Read web page https://example.com', 'قراءة صفحة الويب https://example.com'],
  ['Screenshot of all monitors', 'لقطة لكل الشاشات'],
  ['Screenshot of the main screen', 'لقطة للشاشة الرئيسية'],
  ['Turn on the camera for ONE photo', 'تشغيل الكاميرا لالتقاط صورة واحدة فقط'],
  ['Remember: "I like tea"', 'تذكّر: «I like tea»'],
  ['Forget: "I like tea"', 'نسيان: «I like tea»'],
  ['Search memory for "tea"', 'البحث في الذاكرة عن «tea»'],
  ['Lock the screen', 'قفل الشاشة'],
  ['Put the computer to sleep', 'وضع الجهاز في السكون'],
  ['RESTART the computer in 60 seconds', 'إعادة تشغيل الجهاز بعد 60 ثانية'],
  ['SHUT DOWN the computer in 60 seconds', 'إطفاء الجهاز بعد 60 ثانية'],
  ['Cancel a pending restart/shutdown', 'إلغاء إعادة التشغيل أو الإطفاء المنتظر'],
  ['volume up by ~10%', 'رفع الصوت بنحو 10%'],
  ['volume down by ~4%', 'خفض الصوت بنحو 4%'],
  ['mute toggle', 'كتم الصوت أو إلغاء كتمه'],
  ['play pause', 'تشغيل أو إيقاف مؤقت'],
  ['next track', 'المقطع التالي'],
  ['previous track', 'المقطع السابق'],
];

test('every summary shape the core sends has an Arabic version', () => {
  for (const [en, ar] of SAMPLES) assert.equal(localizeSummary(en, 'ar'), ar, en);
});

test('English stays as sent, and an unknown summary is shown as is rather than broken', () => {
  for (const [en] of SAMPLES) assert.equal(localizeSummary(en, 'en'), en);
  assert.equal(localizeSummary('Something new from a future tool', 'ar'), 'Something new from a future tool');
  assert.equal(localizeSummary(undefined, 'ar'), '');
});

test('arguments are never translated: paths, links and quoted text pass through unchanged', () => {
  assert.equal(localizeSummary('Open app: Open app: x', 'ar'), 'فتح برنامج: Open app: x');
  assert.equal(localizeSummary('Copy to clipboard: "Open app: Notepad"', 'ar'), 'نسخ إلى الحافظة: «Open app: Notepad»');
});

test('a tool line names the action once: the summary, or the tool name when there is no summary', () => {
  const main = readFileSync(new URL('./main.js', import.meta.url), 'utf8');
  const showTool = main.slice(main.indexOf('function showTool('), main.indexOf('function scrollDown('));
  assert.doesNotMatch(showTool, /toolLabel\(tool\)\.toUpperCase\(\)\} · /);
  assert.match(showTool, /localizeSummary\(summary, getLanguage\(\)\) \|\| toolLabel\(tool\)/);
});

test('the approval card and the tool lines show the localized summary and destination', () => {
  const main = readFileSync(new URL('./main.js', import.meta.url), 'utf8');
  assert.match(main, /\$\('confirm-summary'\)\.textContent = localizeSummary\(request\.summary, getLanguage\(\)\)/);
  assert.match(main, /localizeSummary\(summary, getLanguage\(\)\)/);
  assert.match(main, /request\.localEngine \? 'confirm\.destLocal' : 'confirm\.destCloud'/);
  assert.match(main, /classList\.toggle\('cloud', !request\.localEngine\)/);
});
