import SwiftUI

struct ScanView: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var coord: ScanCoordinator
    @Environment(\.colorScheme) var scheme
    @State private var showShare = false
    @State private var showAbout = false
    @State private var selected: Finding?

    private var ar: Bool { app.lang == .ar }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    header
                    scanCard
                    if coord.state == .noNetwork { noNetwork }
                    if coord.state == .done || coord.state == .scanning { results }
                    privacyNote
                }
                .padding(16)
            }
            .background(Theme.bg(scheme).ignoresSafeArea())
            .navigationTitle("")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    // Apple lets the phone, not the app, change an app's language,
                    // so this opens Soor's page in Settings where the choice lives
                    Button { app.openLanguageSettings() } label: {
                        Label(app.lang == .ar ? "اللغة" : "Language", systemImage: "globe")
                            .labelStyle(.titleAndIcon)
                    }
                    .font(.footnote.weight(.medium))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAbout = true } label: { Image(systemName: "info.circle") }
                }
            }
            .sheet(isPresented: $showAbout) { AboutView() }
            .sheet(item: $selected) { f in FindingDetailView(finding: f) }
            .sheet(isPresented: $showShare) {
                ShareSheet(text: coord.reportText(lang: app.lang))
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 10) {
            LogoMark().frame(width: 74, height: 74)
            Text("سُور")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(Theme.ink(scheme))
            Text(ar ? "فاحص أمان شبكة البيت" : "Home network security scanner")
                .font(.subheadline)
                .foregroundStyle(Theme.ink2(scheme))
        }
        .padding(.top, 8)
    }

    // MARK: - Scan card

    private var scanCard: some View {
        VStack(spacing: 14) {
            if coord.state == .scanning {
                ProgressView(value: coord.progressValue)
                    .tint(Theme.navy)
                Text(coord.progressText)
                    .font(.footnote)
                    .foregroundStyle(Theme.ink2(scheme))
            } else {
                Button {
                    coord.start(lang: app.lang)
                } label: {
                    HStack {
                        Image(systemName: "dot.radiowaves.left.and.right")
                        Text(coord.state == .done
                             ? (ar ? "افحص من جديد" : "Scan again")
                             : (ar ? "افحص شبكتي" : "Scan my network"))
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Theme.navy)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                if coord.state == .done {
                    Text(ar
                         ? "فُحص \(coord.deviceCount) جهازًا على شبكتك"
                         : "\(coord.deviceCount) devices scanned on your network")
                        .font(.footnote)
                        .foregroundStyle(Theme.ink2(scheme))
                }
            }
        }
        .padding(18)
        .background(Theme.panel(scheme))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule(scheme)))
    }

    private var noNetwork: some View {
        VStack(spacing: 8) {
            Image(systemName: "wifi.slash").font(.title)
            Text(ar ? "لا توجد شبكة واي فاي" : "No Wi-Fi network")
                .fontWeight(.semibold)
            Text(ar
                 ? "اتصل بشبكة الواي فاي في بيتك ثم افحص، فسُور يفحص الشبكة المتصل بها فقط."
                 : "Connect to your home Wi-Fi, then scan. Soor scans only the network you are on.")
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundStyle(Theme.ink2(scheme))
        }
        .padding(18)
        .frame(maxWidth: .infinity)
        .background(Theme.panel(scheme))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Results

    private var results: some View {
        VStack(alignment: .leading, spacing: 12) {
            if coord.state == .done {
                summaryRow
                if coord.findings.isEmpty {
                    cleanCard
                } else {
                    ForEach(coord.findings) { f in
                        FindingCard(finding: f)
                            .onTapGesture { selected = f }
                    }
                    Button {
                        showShare = true
                    } label: {
                        HStack { Image(systemName: "square.and.arrow.up"); Text(ar ? "شارك التقرير" : "Share report") }
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.navy))
                            .foregroundStyle(Theme.navy)
                    }
                }
            }
        }
    }

    private var summaryRow: some View {
        let counts = coord.summary()
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach([Severity.critical, .high, .medium, .info], id: \.self) { s in
                    if let c = counts[s], c > 0 {
                        SeverityPill(severity: s, count: c, lang: app.lang)
                    }
                }
                if coord.findings.isEmpty {
                    Text(ar ? "لا مخاطر ظاهرة" : "No visible risks")
                        .font(.footnote).foregroundStyle(Theme.ink2(scheme))
                }
            }
        }
    }

    private var cleanCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark.shield.fill")
                .foregroundStyle(Color(hex: 0x52C48D)).font(.title2)
            VStack(alignment: .leading, spacing: 2) {
                Text(ar ? "لا مخاطر ظاهرة" : "No visible risks")
                    .fontWeight(.semibold).foregroundStyle(Theme.ink(scheme))
                Text(ar ? "لم يُعثر على منفذ خطر أو كاميرا مكشوفة." : "No risky ports or exposed cameras found.")
                    .font(.footnote).foregroundStyle(Theme.ink2(scheme))
            }
            Spacer()
        }
        .padding(16)
        .background(Theme.panel(scheme))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(hex: 0x52C48D).opacity(0.4)))
    }

    private var privacyNote: some View {
        HStack(spacing: 10) {
            Image(systemName: "lock.fill").foregroundStyle(Theme.navyLite)
            Text(ar
                 ? "يعمل على جهازك بالكامل، فلا حساب ولا خادم ولا جمع بيانات."
                 : "Runs entirely on your device. No account, no server, no data collection.")
                .font(.caption)
                .foregroundStyle(Theme.ink2(scheme))
            Spacer()
        }
        .padding(14)
        .background(Theme.navy.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Logo

struct LogoMark: View {
    var body: some View {
        Image("LogoMark")
            .resizable()
            .scaledToFit()
            .accessibilityHidden(true)
    }
}

// MARK: - Severity pill

struct SeverityPill: View {
    let severity: Severity
    let count: Int
    let lang: Lang
    @Environment(\.colorScheme) var scheme
    var body: some View {
        let c = Theme.severityColor(severity, scheme)
        return HStack(spacing: 5) {
            Text("\(count)").fontWeight(.bold)
            Text(SoorReport.severityLabel(severity, lang))
        }
        .font(.caption)
        .foregroundStyle(c)
        .padding(.horizontal, 11).padding(.vertical, 5)
        .overlay(Capsule().stroke(c.opacity(0.5)))
    }
}
