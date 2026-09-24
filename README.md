# سُور · Soor

<div dir="rtl">

**سُور** تطبيق يفحص شبكة بيتك كما يفحصها المختبِر، فيكشف الأجهزة المتصلة بها وما تفتحه من منافذ خطرة، ويحذّرك من الكاميرا التي ما زالت على كلمة مرور المصنع ومنفذها مفتوح إلى الإنترنت قبل أن تجدها محركات الفحص العامة، ويعمل داخل الهاتف بلا حساب ولا خادم ولا جمع بيانات.

الموقع: https://soor.3li.info

## ماذا يفعل

سُور يرسم شبكتك ثم يقيّم ما يجده، ويحوّل كل اكتشاف إلى جملة يفهمها غير المختص مع خطوة إصلاح واضحة بالعربية والإنجليزية، فيكشف الكاميرات المكشوفة للإنترنت، والمنافذ الخطرة مثل منفذ تصحيح الأخطاء في صناديق أندرويد والتحكم عن بُعد المكشوف ومشاركة الملفات المفتوحة، وإعدادات الراوتر مثل خاصية UPnP التي تفتح منافذ إلى الإنترنت دون علمك، والأجهزة التي تظهر على الشبكة لأول مرة.

## الكاميرات وكلمة مرور المصنع

الكاميرا تظهر في محركات الفحص العامة لسببين مترابطين، أولهما أن الراوتر مرّر منفذها إلى الإنترنت، وثانيهما أنها ما زالت على كلمة مرور المصنع، لذا يعالج سُور السببين معًا من داخل الشبكة قبل أن يجدها الماسح الخارجي.

سُور لا يجرّب كلمة مرور على أي كاميرا لأن هذا يجعله أداة هجوم، بل يكشف الضعف بثلاث طرق دفاعية، فيقرأ جدول UPnP في الراوتر ليعرف إن كان منفذ الكاميرا مفتوحًا إلى الإنترنت، ويتعرّف على طراز الكاميرا فيقول لك إن هذا الطراز يُشحن ببيانات دخول معروفة للعامة، ويقرأ صفحة دخولها إن أعلنت أنها بلا كلمة مرور أو قبلت البث دون مصادقة.

## لا يجمع بياناتك

سُور يعمل داخل الهاتف بالكامل، إذ تُشحن معرفته كلها فيه فلا يحتاج خادمًا، ونسخة الأندرويد لا تطلب إذن الإنترنت أصلًا فلا تستطيع أن ترسل شيئًا حتى لو أرادت، وهذا مفروض ببنية التطبيق لا بوعد، ويمكن لأي أحد أن يتحقق منه في هذه الشيفرة المفتوحة.

## البنية

المشروع مصدر واحد يجمع المحرك والتطبيقين، فالمحرك ملف واحد بلا اعتماديات في `docs/engine`، ينقل إلى Swift وKotlin ويُختبر بمجموعة متجهات مشتركة في `tests` يجب أن تعطي الحكم نفسه في المحركات الثلاثة، أما قاعدة المعرفة فملفات JSON في `docs/data`، تحمل المنافذ والخدمات وبصمات الكاميرات وأدلة الإصلاح بالعربية والإنجليزية.

</div>

## What it is

**Soor** scans your home network the way a tester would. It finds the devices on your network and the risky ports they leave open, and warns you about the camera still on its factory password with its port open to the internet, before public scanning engines find it. It runs inside the phone, with no account, no server, and no data collection.

Site: https://soor.3li.info

## Cameras and the factory password

A camera appears on public scanning engines for two linked reasons: the router forwarded its port to the internet, and it is still on its factory password. Soor addresses both from inside the network before an outside scanner finds it.

Soor never tries a password on any camera, because that would make it an attack tool. Instead it detects the weakness three defensive ways: it reads the router's UPnP table to see whether the camera's port is open to the internet, it identifies the camera model and tells you that model ships with publicly known login details, and it reads the login page when it announces it has no password set or serves the stream without authentication. The factory-credential list is here to raise awareness that they must be changed, not to be used.

## Privacy

Soor runs entirely on the device. All its knowledge is shipped inside it, so it needs no server, and the Android version does not even request internet permission, so it cannot send anything even if it wanted to. This is enforced by the app's structure, not promised, and anyone can verify it in this open source.

## Structure

One repository holds the engine and both apps. The engine is a single dependency-free file in `docs/engine`, ported to Swift and Kotlin and checked against a shared set of test vectors in `tests` that must produce the same verdict across all three engines. The knowledge base is JSON in `docs/data`, carrying ports and services, camera fingerprints, and remediation guidance in Arabic and English.

```
docs/engine/   soor.js (analysis), report.js (bilingual wording)
docs/data/     services.json, cameras.json
docs/          the site served at soor.3li.info
tests/         shared vectors and the Node runner
ios/Soor/      the iOS app (SwiftUI, real on-device scan)
ios/SoorEngine/  the engine as a SwiftPM package, tested against the shared vectors
android/       the Android app (Kotlin), next
tools/         check-sync.sh, the guard that keeps every engine copy identical
```

The iOS app scans for real. It finds the phone's subnet, sweeps it for live
hosts, probes common service ports and reads the banners they volunteer, and
reads the router's UPnP port-forward table to tell an internet-exposed device
from a safe local one. It tries no passwords and exploits nothing. The Swift
engine is the JS engine ported line for line, and CI runs both against the same
`tests/vectors.json`, so a finding is identical on every platform.

## Tests

```
npm test
```

## Scope

Soor is a defensive tool. It scans only the network the phone is on, does not go beyond its private addresses, tries no passwords, and exploits nothing. It knocks on doors and reads what devices announce about themselves.

## License

MIT © Ali AlEnezi
