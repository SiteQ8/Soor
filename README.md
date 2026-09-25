# سُور · Soor

<div dir="rtl">

**سُور** تطبيق يفحص شبكة بيتك كما يفحصها المختبِر، فيجد كل جهاز متصل بها ويقرأ ما يفتحه من منافذ، ويميّز المكشوف منها إلى الإنترنت من الآمن داخل الشبكة، ثم يرتّب ما وجده بحسب الخطورة مع خطوة إصلاح لكل اكتشاف، ومن ذلك التحذير من الكاميرات المكشوفة، ويعمل داخل الهاتف بلا حساب ولا خادم ولا جمع بيانات.

الموقع: https://soor.3li.info

## ماذا يفعل

سُور يرسم شبكتك ثم يقيّم ما يجده، ويحوّل كل اكتشاف إلى جملة يفهمها غير المختص مع خطوة إصلاح واضحة بالعربية والإنجليزية، فيكشف الكاميرات المكشوفة للإنترنت، والمنافذ الخطرة مثل منفذ تصحيح الأخطاء في صناديق أندرويد والتحكم عن بُعد المكشوف ومشاركة الملفات المفتوحة، وإعدادات الراوتر مثل خاصية UPnP التي تفتح منافذ إلى الإنترنت دون علمك، والأجهزة التي تظهر على الشبكة لأول مرة.

## الكاميرات وكلمة مرور المصنع

الكاميرا تظهر في محركات الفحص العامة لسببين مترابطين، أولهما أن الراوتر مرّر منفذها إلى الإنترنت، وثانيهما أنها ما زالت على كلمة مرور المصنع، لذا يعالج سُور السببين معًا من داخل الشبكة قبل أن يجدها الماسح الخارجي.

سُور لا يجرّب كلمة مرور على أي كاميرا لأن هذا يجعله أداة هجوم، بل يكشف الضعف بثلاث طرق دفاعية، فيقرأ جدول UPnP في الراوتر ليعرف إن كان منفذ الكاميرا مفتوحًا إلى الإنترنت، ويتعرّف على طراز الكاميرا فيقول لك إن هذا الطراز يُشحن ببيانات دخول معروفة للعامة، ويقرأ صفحة دخولها إن أعلنت أنها بلا كلمة مرور أو قبلت البث دون مصادقة.

## لا يجمع بياناتك

سُور يعمل داخل الهاتف بالكامل، إذ تُشحن معرفته كلها فيه فلا يحتاج خادمًا، ولا يتصل إلا بعناوين داخل شبكة البيت، لأن في شيفرته قاعدة ترفض أي عنوان خارجها قبل الاتصال به، وتُختبر آليًا مع كل بناء، ويمكن لأي أحد أن يتحقق منها في هذه الشيفرة المفتوحة.

## البنية

المشروع مصدر واحد يجمع المحرك والتطبيقين، فالمحرك ملف واحد بلا اعتماديات في `docs/engine`، ينقل إلى Swift وKotlin ويُختبر بمجموعة متجهات مشتركة في `tests` يجب أن تعطي الحكم نفسه في المحركات الثلاثة، أما قاعدة المعرفة فملفات JSON في `docs/data`، تحمل المنافذ والخدمات وبصمات الكاميرات وأدلة الإصلاح بالعربية والإنجليزية.

</div>

## What it is

**Soor** scans your home network the way a tester would. It finds every device on your network and reads the ports each one leaves open, tells what is exposed to the internet from what is safe inside, then ranks what it found by severity with a clear fix for each, cameras among them. It runs inside the phone, with no account, no server, and no data collection.

Site: https://soor.3li.info

## Cameras and the factory password

A camera appears on public scanning engines for two linked reasons: the router forwarded its port to the internet, and it is still on its factory password. Soor addresses both from inside the network before an outside scanner finds it.

Soor never tries a password on any camera, because that would make it an attack tool. Instead it detects the weakness three defensive ways: it reads the router's UPnP table to see whether the camera's port is open to the internet, it identifies the camera model and tells you that model ships with publicly known login details, and it reads the login page when it announces it has no password set or serves the stream without authentication. The factory-credential list is here to raise awareness that they must be changed, not to be used.

## Privacy

Soor runs entirely on the device. All its knowledge is shipped inside it, so it needs no server, and it only ever connects to addresses inside the home network: a rule in its code refuses any other address before connecting, and that rule is tested on every build. Anyone can verify it in this open source.

## Structure

One repository holds the engine and both apps. The engine is a single dependency-free file in `docs/engine`, ported to Swift and Kotlin and checked against a shared set of test vectors in `tests` that must produce the same verdict across all three engines. The knowledge base is JSON in `docs/data`, carrying ports and services, camera fingerprints, and remediation guidance in Arabic and English.

```
docs/engine/   soor.js (analysis), report.js (bilingual wording)
docs/data/     services.json, cameras.json
docs/          the site served at soor.3li.info
tests/         shared vectors and the Node runner
ios/Soor/      the iOS app (SwiftUI, real on-device scan)
ios/SoorEngine/  the engine as a SwiftPM package, tested against the shared vectors
android/       the Android app (Kotlin, real on-device scan, home-network addresses only)
tools/         check-sync.sh, the guard that keeps every engine copy identical
```

The iOS app scans for real. It finds the phone's subnet, sweeps it for live
hosts, probes common service ports and reads the banners they volunteer, and
reads the router's UPnP port-forward table to tell an internet-exposed device
from a safe local one. It tries no passwords and exploits nothing. The Swift
engine is the JS engine ported line for line, and CI runs both against the same
`tests/vectors.json`, so a finding is identical on every platform.

The Android app does the same work in Kotlin. Android requires the INTERNET
permission for any network connection, even to a device in the same room, so
the app holds it. What keeps Soor inside the home is `LocalOnly`: every
connection passes it before it is made, and it refuses anything but private
home-network addresses and never resolves a hostname. `LocalOnlyTest` proves the
rule and fails the build if any connection in the scanner skips it, and CI fails
if the app ever asks for a permission beyond the two it needs.

## Android release

A release bundle is signed with the upload key, which never enters the
repository. The build reads it from the environment:

```sh
export SOOR_KEYSTORE=/path/to/soor-upload.keystore
export SOOR_KEY_ALIAS=soor-upload
export SOOR_KEYSTORE_PASSWORD=...
cd android && ./gradlew :app:bundleRelease
```

The first upload of a new app to Google Play has to be done by hand in the
Play Console, because the package name only exists once a first build is
uploaded. Store texts and graphics are in `play/`.

## Tests

```
npm test
```

## Scope

Soor is a defensive tool. It scans only the network the phone is on, does not go beyond its private addresses, tries no passwords, and exploits nothing. It knocks on doors and reads what devices announce about themselves.

## License

MIT © Ali AlEnezi
