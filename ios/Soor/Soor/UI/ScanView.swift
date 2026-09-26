import SwiftUI

// The screens: start, scanning and results, the same three the Android app has.

enum Sheet: Identifiable {
    case about
    case finding(Finding)
    case device(DeviceInfo)

    var id: String {
        switch self {
        case .about: return "about"
        case .finding(let f): return "f:" + f.id.uuidString
        case .device(let d): return "d:" + d.ip
        }
    }
}

struct ScanView: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var coord: ScanCoordinator
    @State private var sheet: Sheet?
    @State private var showShare = false

    private var ar: Bool { app.lang == .ar }

    var body: some View {
        ZStack {
            Background()
            VStack(spacing: 0) {
                TopBar(ar: ar, showBack: coord.state == .done, onBack: { coord.home() },
                       onLang: { app.openLanguageSettings() }, onAbout: { sheet = .about })
                switch coord.state {
                case .scanning:
                    ScanningScreen(ar: ar)
                case .done:
                    ResultsScreen(ar: ar, onFinding: { sheet = .finding($0) }, onDevice: { sheet = .device($0) }, onShare: { showShare = true })
                default:
                    HomeScreen(ar: ar)
                }
            }
        }
        .sheet(item: $sheet) { s in
            switch s {
            case .about:
                AboutSheet(ar: ar)
            case .finding(let f):
                FindingSheet(finding: f, device: coord.devices.first { $0.ip == f.host }, ar: ar)
            case .device(let d):
                DeviceSheet(device: d, findings: coord.findings.filter { $0.host == d.ip }, ar: ar,
                            onFinding: { f in sheet = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { sheet = .finding(f) } })
            }
        }
        .sheet(isPresented: $showShare) { ShareSheet(text: coord.reportText(lang: app.lang)) }
        .onAppear { applyDemoIfAsked() }
    }

    /// The store screenshots are taken from the real app on the simulator: the
    /// launch argument -soor-demo <scene> opens the app on that scene with a
    /// sample home. Nothing sets it in normal use.
    private func applyDemoIfAsked() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-soor-demo"), i + 1 < args.count else { return }
        switch args[i + 1] {
        case "scanning": coord.demoScanning()
        case "results": coord.loadDemo()
        case "devices": coord.loadDemo()
        case "detail":
            coord.loadDemo()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { sheet = coord.findings.first.map { Sheet.finding($0) } }
        case "device":
            coord.loadDemo()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { sheet = coord.devices.dropFirst().first.map { Sheet.device($0) } }
        case "about": sheet = .about
        default: break
        }
    }
}

struct Background: View {
    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            GeometryReader { g in
                RadialGradient(colors: [Theme.navy.opacity(0.40), .clear], center: .top, startRadius: 0, endRadius: g.size.width * 1.05)
                    .ignoresSafeArea()
            }
        }
    }
}

struct TopBar: View {
    let ar: Bool
    let showBack: Bool
    let onBack: () -> Void
    let onLang: () -> Void
    let onAbout: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            if showBack {
                Button(action: onBack) {
                    Image(systemName: "chevron.backward").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.ink2)
                        .frame(width: 34, height: 34)
                        .overlay(Circle().stroke(Theme.rule, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
            Image("Mark").resizable().frame(width: 30, height: 30).clipShape(RoundedRectangle(cornerRadius: 9))
            Text(t(ar, "سُور", "Soor")).font(Theme.font(19, .bold)).foregroundStyle(Theme.ink)
            Spacer()
            Pill(text: t(ar, "English", "العربية"), action: onLang)
            Button(action: onAbout) {
                Image(systemName: "info.circle").font(.system(size: 20)).foregroundStyle(Theme.ink2).frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }
}

// MARK: - Home

struct HomeScreen: View {
    @EnvironmentObject var coord: ScanCoordinator
    let ar: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                ZStack {
                    Radar(sweeping: false)
                    Image("Mark").resizable().frame(width: 108, height: 108)
                        .clipShape(RoundedRectangle(cornerRadius: 30))
                        .overlay(RoundedRectangle(cornerRadius: 30).stroke(Color.white.opacity(0.12), lineWidth: 1))
                }
                .frame(width: 232, height: 232)
                Text(t(ar, "افحص شبكة بيتك", "Scan your home network"))
                    .font(Theme.font(28, .bold)).foregroundStyle(Theme.ink).multilineTextAlignment(.center)
                Text(t(ar, "اعرف كل جهاز عليها وما يفتحه من منافذ، وما هو مكشوف منها للإنترنت",
                         "See every device on it, the ports each one opens, and what is exposed to the internet"))
                    .font(Theme.font(15)).foregroundStyle(Theme.ink2).multilineTextAlignment(.center).lineSpacing(5).padding(.top, 8)
                PrimaryButton(text: t(ar, "افحص شبكتي", "Scan my network")) { coord.start() }.padding(.top, 24)
                Text(t(ar, "يعمل على جهازك، ولا يجمع أي بيانات", "Runs on your phone and collects no data"))
                    .font(Theme.font(12.5)).foregroundStyle(Theme.ink3).padding(.top, 10)
                if coord.state == .noNetwork { NoNetwork(ar: ar).padding(.top, 16) }
                if coord.lastScan != nil { LastScanCard(ar: ar).padding(.top, 18) }
                SectionTitle(text: t(ar, "ماذا يفحص", "What it checks")).padding(.top, 26)
                FeatureRow(kind: .computer, title: t(ar, "كل جهاز على شبكتك", "Every device on your network"), sub: t(ar, "وما يفتحه من منافذ وخدمات", "and the ports and services it opens"))
                FeatureRow(kind: .router, title: t(ar, "ما هو مكشوف للإنترنت", "What is exposed to the internet"), sub: t(ar, "من جدول المنافذ الممرَّرة في الراوتر", "from the router's forwarded ports"))
                FeatureRow(kind: .camera, title: t(ar, "الكاميرات", "Cameras"), sub: t(ar, "ويحذّرك من المكشوف منها للإنترنت", "warning you about any exposed to the internet"))
                FeatureRow(kind: .unknown, title: t(ar, "الأجهزة الجديدة", "New devices"), sub: t(ar, "حين يظهر على شبكتك جهاز لأول مرة", "when one appears on your network for the first time"))
                PrivacyNote(ar: ar).padding(.top, 16)
            }
            .padding(.horizontal, 20).padding(.bottom, 24)
        }
    }
}

struct FeatureRow: View {
    let kind: DeviceKind
    let title: String
    let sub: String
    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(Theme.navy.opacity(0.28)).frame(width: 44, height: 44)
                DeviceGlyph(kind: kind, tint: Theme.signal).frame(width: 28, height: 28)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Theme.font(15, .semibold)).foregroundStyle(Theme.ink)
                Text(sub).font(Theme.font(13)).foregroundStyle(Theme.ink2)
            }
            Spacer()
        }
        .padding(14)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule, lineWidth: 1))
        .padding(.vertical, 5)
    }
}

struct PrivacyNote: View {
    let ar: Bool
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "lock").foregroundStyle(Theme.signal).font(.system(size: 17))
            Text(t(ar, "لا يتصل سُور إلا بعناوين داخل شبكة بيتك، ولا حساب فيه ولا خادم، ولا يخرج بشيء عنك",
                     "Soor only connects to addresses inside your home network. No account, no server, and nothing about you leaves the phone."))
                .font(Theme.font(13)).foregroundStyle(Theme.ink2).lineSpacing(4)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.navy.opacity(0.14), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.navy.opacity(0.35), lineWidth: 1))
    }
}

struct NoNetwork: View {
    let ar: Bool
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle").foregroundStyle(Theme.high).font(.system(size: 18))
            VStack(alignment: .leading, spacing: 2) {
                Text(t(ar, "لست على شبكة واي فاي", "You are not on Wi-Fi")).font(Theme.font(15, .semibold)).foregroundStyle(Theme.ink)
                Text(t(ar, "اتصل بشبكة الواي فاي في بيتك ثم افحص، فسُور يفحص الشبكة المتصل بها فقط.",
                         "Connect to your home Wi-Fi and scan again. Soor only scans the network you are on."))
                    .font(Theme.font(13)).foregroundStyle(Theme.ink2).lineSpacing(4)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.high.opacity(0.10), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.high.opacity(0.45), lineWidth: 1))
    }
}

struct LastScanCard: View {
    @EnvironmentObject var coord: ScanCoordinator
    let ar: Bool
    var body: some View {
        Button { coord.showResults() } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Circle().fill(toneColor(coord.findings)).frame(width: 10, height: 10)
                    Text(t(ar, "آخر فحص", "Last scan")).font(Theme.font(15, .semibold)).foregroundStyle(Theme.ink)
                    Spacer()
                    Mono(text: timeOfDay(coord.lastScan ?? Date()), color: Theme.ink3)
                }
                Text(Words.found(coord.devices.count, ar) + t(ar, "، ", ", ") + t(ar, "\(coord.findings.count) ملاحظة", "\(coord.findings.count) findings"))
                    .font(Theme.font(13)).foregroundStyle(Theme.ink2)
                Text(t(ar, "عرض النتائج", "Show the results")).font(Theme.font(13, .semibold)).foregroundStyle(Theme.navyLite).padding(.top, 2)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Scanning

struct ScanningScreen: View {
    @EnvironmentObject var coord: ScanCoordinator
    let ar: Bool

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 0) {
                    PhaseStepper(phase: coord.phase, ar: ar).padding(.top, 4)
                    if let net = coord.network {
                        Text(verbatim: t(ar, "الشبكة ", "Network ") + ltr(net.prefix + ".x") + (net.gateway.map { "  ·  " + t(ar, "الراوتر ", "Router ") + ltr($0) } ?? ""))
                            .font(Theme.mono(12.5)).foregroundStyle(Theme.ink3).padding(.top, 10)
                    }
                    Radar(sweeping: true, dots: coord.liveHosts.map { radarDot($0) })
                        .aspectRatio(1, contentMode: .fit)
                        .frame(maxWidth: 240)
                        .padding(.top, 4)
                    Text(phaseTitle).font(Theme.font(22, .bold)).foregroundStyle(Theme.ink).padding(.top, 6)
                    Text(phaseSub).font(Theme.font(14)).foregroundStyle(Theme.ink2).multilineTextAlignment(.center).lineSpacing(4).padding(.top, 4)
                    ProgressBar(value: coord.progress).padding(.top, 16)
                    TimelineView(.periodic(from: Date(), by: 1)) { tl in
                        HStack(spacing: 10) {
                            Stat(value: "\(coord.liveHosts.count)", label: t(ar, "الأجهزة", "Devices"))
                            Stat(value: "\(coord.portsChecked)", label: t(ar, "المنافذ", "Ports"))
                            Stat(value: clock(tl.date.timeIntervalSince(coord.startedAt ?? tl.date)), label: t(ar, "الوقت", "Elapsed"))
                        }
                    }
                    .padding(.top, 14)
                    LiveFeed(events: coord.events, ar: ar).padding(.top, 14)
                }
                .padding(.horizontal, 20).padding(.bottom, 16)
            }
            // everything scrolls except the stop button, which stays within reach
            SecondaryButton(text: coord.stopping ? t(ar, "جارٍ الإيقاف، ويُحفظ ما وُجد", "Stopping, keeping what was found") : t(ar, "أوقف الفحص", "Stop the scan"),
                            icon: "xmark", tint: Theme.crit, border: Theme.crit.opacity(0.5), fill: Theme.crit.opacity(0.08)) {
                coord.stop()
            }
            .disabled(coord.stopping)
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 16)
        }
    }

    private var phaseTitle: String {
        switch coord.phase {
        case .discover: return t(ar, "البحث عن الأجهزة", "Finding devices")
        case .probe: return t(ar, "فحص المنافذ", "Checking ports")
        case .judge: return t(ar, "ترتيب النتائج", "Ranking the findings")
        }
    }

    private var phaseSub: String {
        switch coord.phase {
        case .discover: return t(ar, "يسأل كل عنوان في شبكتك هل فيه جهاز", "Asking every address on your network whether a device is there")
        case .probe: return t(ar, "يقرأ ما تعلنه خدمات كل جهاز عن نفسها", "Reading what each device's services announce about themselves")
        case .judge: return t(ar, "يرتّب المحرك ما وجده بحسب الخطورة", "The engine ranks what it found by severity")
        }
    }
}

struct ProgressBar: View {
    let value: Double
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.panel2)
                Capsule().fill(Theme.signal).frame(width: max(6, g.size.width * CGFloat(min(1, max(0, value)))))
            }
        }
        .frame(height: 6)
    }
}

struct Stat: View {
    let value: String
    let label: String
    var body: some View {
        VStack(spacing: 2) {
            Text(verbatim: ltr(value)).font(Theme.mono(22, bold: true)).foregroundStyle(Theme.signal)
            Text(label).font(Theme.font(12)).foregroundStyle(Theme.ink2)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12).padding(.horizontal, 8)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule, lineWidth: 1))
    }
}

struct PhaseStepper: View {
    let phase: ScanCoordinator.Phase
    let ar: Bool

    var body: some View {
        let steps = [(ScanCoordinator.Phase.discover, t(ar, "اكتشاف", "Discover")), (.probe, t(ar, "المنافذ", "Ports")), (.judge, t(ar, "الحكم", "Verdict"))]
        let current = steps.firstIndex { $0.0 == phase } ?? 0
        HStack(alignment: .top, spacing: 0) {
            ForEach(0..<3, id: \.self) { i in
                let done = i < current
                let active = i == current
                VStack(spacing: 4) {
                    ZStack {
                        Circle().fill(done ? Theme.signal : (active ? Theme.navy : Theme.panel2)).frame(width: 26, height: 26)
                        Circle().stroke(active || done ? Theme.signal : Theme.rule, lineWidth: 1.5).frame(width: 26, height: 26)
                        if done { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.bg) }
                        else { Text(verbatim: "\(i + 1)").font(Theme.mono(12, bold: true)).foregroundStyle(active ? Theme.ink : Theme.ink3) }
                    }
                    Text(steps[i].1).font(Theme.font(11.5, active ? .semibold : .regular)).foregroundStyle(active ? Theme.ink : Theme.ink3)
                }
                if i < 2 {
                    Rectangle().fill(i < current ? Theme.signal : Theme.rule).frame(height: 2).padding(.horizontal, 6).padding(.top, 12)
                }
            }
        }
        .frame(maxWidth: 300)
    }
}

struct LiveFeed: View {
    let events: [ScanEvent]
    let ar: Bool
    var body: some View {
        let shown = Array(events.suffix(5))
        VStack(alignment: .leading, spacing: 6) {
            GoldLabel(text: t(ar, "ماذا يجري الآن", "What is happening now"))
            if shown.isEmpty { Text(t(ar, "يبدأ...", "Starting...")).font(Theme.font(13)).foregroundStyle(Theme.ink3) }
            ForEach(Array(shown.enumerated()), id: \.element.id) { i, e in
                let last = i == shown.count - 1
                HStack(spacing: 10) {
                    Circle().fill(last ? Theme.signal : Theme.ink3).frame(width: 6, height: 6)
                    Text(eventText(e, ar)).font(Theme.font(13.5)).foregroundStyle(last ? Theme.ink : Theme.ink3).lineLimit(1)
                }
                .padding(.vertical, 2)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule, lineWidth: 1))
    }
}

// MARK: - Results

enum Tone { case crit, high, med, ok }

func toneOf(_ fs: [Finding]) -> Tone {
    if fs.contains(where: { $0.exposed }) { return .crit }
    if fs.contains(where: { $0.severity == .critical || $0.kind == "open-auth" || $0.reason == "no-auth" }) { return .high }
    if fs.contains(where: { $0.severity == .high || $0.severity == .medium }) { return .med }
    return .ok
}

func toneColor(_ fs: [Finding]) -> Color {
    switch toneOf(fs) {
    case .crit: return Theme.crit
    case .high: return Theme.high
    case .med: return Theme.med
    case .ok: return Theme.ok
    }
}

struct ResultsScreen: View {
    @EnvironmentObject var coord: ScanCoordinator
    let ar: Bool
    let onFinding: (Finding) -> Void
    let onDevice: (DeviceInfo) -> Void
    let onShare: () -> Void

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                StatusBanner(ar: ar)
                SeverityPills(ar: ar)
                if coord.findings.isEmpty {
                    CleanCard(ar: ar)
                } else {
                    SectionTitle(text: t(ar, "ما يستحق انتباهك", "Worth your attention"), count: coord.findings.count)
                    ForEach(coord.findings) { f in
                        FindingCard(finding: f, device: coord.devices.first { $0.ip == f.host }, ar: ar,
                                    isNew: coord.newFindings.contains(KnownDevices.signature(f))) { onFinding(f) }
                    }
                }
                if !coord.checks.isEmpty {
                    SectionTitle(text: t(ar, "الشبكة نفسها", "The network itself"))
                    ForEach(coord.checks) { c in CheckRow(check: c, ar: ar) }
                }
                SectionTitle(text: t(ar, "أجهزة شبكتك", "Devices on your network"), count: coord.devices.count)
                ForEach(coord.devices) { d in
                    DeviceRow(device: d, ar: ar) { onDevice(d) }
                }
                if coord.partial {
                    Note(text: t(ar, "توقف الفحص قبل اكتماله، فالنتائج أعلاه عمّا وصل إليه فقط، وافحص من جديد لترى الشبكة كلها.",
                                    "The scan was stopped before it finished, so the results above cover only what it reached. Scan again to see the whole network."))
                }
                if coord.firstScan {
                    Note(text: t(ar, "هذا أول فحص لهذه الشبكة، فحفظ سُور في هاتفك وحده قائمة أجهزتها ليخبرك في المرات القادمة بأي جهاز جديد يظهر عليها.",
                                    "This is the first scan of this network, so Soor kept its device list on this phone only, to tell you next time about any new device."))
                }
                ScanDetails(ar: ar)
                HStack(spacing: 10) {
                    SecondaryButton(text: t(ar, "شارك التقرير", "Share report"), icon: "square.and.arrow.up", action: onShare)
                    PrimaryButton(text: t(ar, "افحص من جديد", "Scan again")) { coord.start() }
                }
                .padding(.top, 6)
            }
            .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 28)
        }
    }
}

struct StatusBanner: View {
    @EnvironmentObject var coord: ScanCoordinator
    let ar: Bool

    var body: some View {
        let tone = toneOf(coord.findings)
        let col = toneColor(coord.findings)
        let title: String = {
            switch tone {
            case .crit: return t(ar, "في السور ثغرة، وبيتك مرئي من الإنترنت", "There is a breach in the wall, your home is visible from the internet")
            case .high: return t(ar, "السور سليم، لكنّ في الداخل ما يحتاج معالجة الآن", "The wall holds, but something inside needs attention now")
            case .med: return t(ar, "السور سليم، وبقيت أمور تستحق المراجعة", "The wall holds, a few things are worth reviewing")
            case .ok: return t(ar, "السور سليم ولا شيء يستحق القلق", "The wall holds and nothing needs worrying about")
            }
        }()
        let newCount = coord.devices.filter { $0.isNew }.count
        var sub = Words.found(coord.devices.count, ar)
        if newCount > 0 { sub += t(ar, "، ", ", ") + Words.newOnes(newCount, ar) }
        if coord.fixedCount > 0 { sub += t(ar, "، و", ", and ") + Words.fixed(coord.fixedCount, ar) }
        return VStack(alignment: .leading, spacing: 0) {
            WallStrip(breached: tone == .crit, color: tone == .crit ? Theme.ink.opacity(0.85) : col.opacity(0.9)).frame(height: 18)
            Text(title).font(Theme.font(20, .bold)).foregroundStyle(Theme.ink).lineSpacing(6).padding(.top, 14)
            Text(sub).font(Theme.font(14)).foregroundStyle(Theme.ink2).lineSpacing(4).padding(.top, 6)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [col.opacity(0.18), Theme.panel], startPoint: .top, endPoint: .bottom), in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(col.opacity(0.45), lineWidth: 1))
    }
}

struct SeverityPills: View {
    @EnvironmentObject var coord: ScanCoordinator
    let ar: Bool
    var body: some View {
        let counts = coord.summary()
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach([Severity.critical, .high, .medium, .info], id: \.self) { s in
                    let n = (counts[s] ?? 0) + (s == .info ? (counts[.low] ?? 0) : 0)
                    if n > 0 {
                        let c = Theme.severity(s)
                        Text(verbatim: "\(n)  " + SoorReport.severityLabel(s, ar ? .ar : .en))
                            .font(Theme.font(13, .medium)).foregroundStyle(c)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .overlay(Capsule().stroke(c.opacity(0.55), lineWidth: 1))
                    }
                }
            }
        }
    }
}

struct CleanCard: View {
    let ar: Bool
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark.circle").foregroundStyle(Theme.ok).font(.system(size: 20))
            Text(t(ar, "لم يجد سُور منفذًا خطرًا ولا جهازًا مكشوفًا", "No risky port and no exposed device")).font(Theme.font(15)).foregroundStyle(Theme.ink)
            Spacer()
        }
        .padding(16)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.ok.opacity(0.4), lineWidth: 1))
    }
}

struct FindingCard: View {
    @EnvironmentObject var coord: ScanCoordinator
    let finding: Finding
    let device: DeviceInfo?
    let ar: Bool
    let isNew: Bool
    let onTap: () -> Void

    var body: some View {
        let r = SoorReport.render(finding, coord.knowledge, ar ? .ar : .en)
        let col = Theme.severity(finding.severity)
        Button(action: onTap) {
            HStack(spacing: 0) {
                Rectangle().fill(col).frame(width: 4)
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top, spacing: 6) {
                        SevChip(label: r.severityLabel, color: col)
                        if isNew { Badge(label: t(ar, "جديد", "New"), color: Theme.signal) }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 1) {
                            Mono(text: hostPort(finding))
                            if let d = device { Text(nameOf(d, ar)).font(Theme.font(11.5)).foregroundStyle(Theme.ink3).lineLimit(1) }
                        }
                    }
                    Text(r.title).font(Theme.font(16, .semibold)).foregroundStyle(Theme.ink).lineSpacing(4).padding(.top, 8)
                    if !r.detail.isEmpty {
                        Text(firstSentence(r.detail)).font(Theme.font(13.5)).foregroundStyle(Theme.ink2).lineSpacing(4).lineLimit(2).padding(.top, 4)
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }
}

struct DeviceRow: View {
    let device: DeviceInfo
    let ar: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(Theme.bg2).frame(width: 46, height: 46)
                    Circle().stroke(Theme.severity(device.worst), lineWidth: 2).frame(width: 46, height: 46)
                    DeviceGlyph(kind: device.kind, tint: Theme.ink).frame(width: 26, height: 26)
                }
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 8) {
                        Text(nameOf(device, ar)).font(Theme.font(15, .semibold)).foregroundStyle(Theme.ink).lineLimit(1)
                        if device.isNew { Badge(label: t(ar, "جديد", "New"), color: Theme.signal) }
                    }
                    Mono(text: device.ip)
                    if device.ports.isEmpty {
                        Text(t(ar, "لا منافذ مفتوحة", "No open ports")).font(Theme.font(12)).foregroundStyle(Theme.ink3)
                    } else {
                        Text(verbatim: ltr(device.ports.map(String.init).joined(separator: " · "))).font(Theme.mono(12)).foregroundStyle(Theme.ink3).lineLimit(1)
                    }
                }
                Spacer()
            }
            .padding(12)
            .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct ScanDetails: View {
    @EnvironmentObject var coord: ScanCoordinator
    let ar: Bool
    var body: some View {
        var rows: [(String, String)] = []
        if let n = coord.network { rows.append((t(ar, "الشبكة", "Network"), n.prefix + ".x")) }
        if let g = coord.network?.gateway { rows.append((t(ar, "الراوتر", "Router"), g)) }
        rows.append((t(ar, "الأجهزة", "Devices"), "\(coord.devices.count)"))
        rows.append((t(ar, "المنافذ المفحوصة", "Ports checked"), "\(coord.portsChecked)"))
        if let d = coord.duration { rows.append((t(ar, "المدة", "Duration"), clock(d))) }
        if let l = coord.lastScan { rows.append((t(ar, "الوقت", "Time"), timeOfDay(l))) }
        return VStack(alignment: .leading, spacing: 6) {
            GoldLabel(text: t(ar, "تفاصيل الفحص", "Scan details"))
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack {
                    Text(row.0).font(Theme.font(13.5)).foregroundStyle(Theme.ink2)
                    Spacer()
                    Text(verbatim: ltr(row.1)).font(Theme.mono(13.5)).foregroundStyle(Theme.ink)
                }
                .padding(.vertical, 3)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule, lineWidth: 1))
    }
}
