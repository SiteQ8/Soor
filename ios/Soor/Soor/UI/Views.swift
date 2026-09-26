import SwiftUI
import UIKit

// The drawings, the shared pieces and the sheets. The same shapes the Android
// app and the site's lab draw, so the three speak one visual language.

func t(_ ar: Bool, _ a: String, _ e: String) -> String { ar ? a : e }
func ltr(_ s: String) -> String { "\u{2066}\(s)\u{2069}" }
func clock(_ seconds: TimeInterval) -> String {
    let s = max(0, Int(seconds))
    return String(format: "%02d:%02d", s / 60, s % 60)
}
func timeOfDay(_ d: Date) -> String {
    let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "HH:mm"
    return f.string(from: d)
}
func nameOf(_ d: DeviceInfo, _ ar: Bool) -> String { d.name ?? DeviceKinds.label(d.kind, ar) }
func hostPort(_ f: Finding) -> String { f.port > 0 ? "\(f.host):\(f.port)" : f.host }
func firstSentence(_ s: String) -> String {
    if let i = s.firstIndex(where: { $0 == "." || $0 == "؟" || $0 == "!" }), i > s.startIndex, i < s.index(before: s.endIndex) {
        return String(s[...i])
    }
    return s
}
func portName(_ p: Int, _ ar: Bool) -> String {
    switch p {
    case 443, 8443: return t(ar, "ويب مشفّر", "Encrypted web")
    case 62078: return t(ar, "خدمة أجهزة Apple", "Apple device service")
    case 7000: return "AirPlay"
    case 8008, 8009: return "Chromecast"
    case 9100: return t(ar, "طباعة مباشرة", "Raw printing")
    case 631: return t(ar, "طباعة IPP", "IPP printing")
    case 548: return t(ar, "مشاركة ملفات Apple", "Apple file sharing")
    default: return t(ar, "خدمة", "Service")
    }
}
func eventText(_ e: ScanEvent, _ ar: Bool) -> String {
    let ip = e.ip.map { ltr($0) } ?? ""
    switch e.kind {
    case "start": return t(ar, "بدأ الفحص", "Scan started")
    case "announce": return t(ar, "يسأل الأجهزة أن تعلن عن نفسها", "Asking devices to announce themselves")
    case "announced": return t(ar, "أعلن \(ip) عن نفسه", "\(ip) announced itself")
    case "router": return t(ar, "يقرأ جدول المنافذ في الراوتر", "Reading the router's port table")
    case "upnp-on": return t(ar, "UPnP مفعّل في الراوتر", "UPnP is on at the router")
    case "upnp-off": return t(ar, "UPnP معطّل في الراوتر", "UPnP is off at the router")
    case "found": return t(ar, "عُثر على \(ip)", "Found \(ip)")
    case "probe": return t(ar, "يفحص منافذ \(ip)", "Checking the ports of \(ip)")
    case "named": return t(ar, "عرف اسم \(ip)", "Learned the name of \(ip)")
    case "judge": return t(ar, "المحرك يرتّب ما وجده", "The engine ranks what it found")
    case "stop": return t(ar, "أوقفتَ الفحص", "You stopped the scan")
    default: return e.kind
    }
}

// MARK: - Drawings

struct DeviceGlyph: View {
    let kind: DeviceKind
    let tint: Color

    var body: some View {
        ZStack {
            Canvas { ctx, size in draw(&ctx, size) }
            if kind == .unknown { Text("?").font(Theme.font(13, .bold)).foregroundStyle(tint) }
        }
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize) {
        let s = min(size.width, size.height) * 0.36
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let w = min(size.width, size.height) * 0.075
        let style = StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round)
        func rr(_ x: CGFloat, _ y: CGFloat, _ width: CGFloat, _ height: CGFloat, _ r: CGFloat) {
            ctx.stroke(Path(roundedRect: CGRect(x: c.x + x, y: c.y + y, width: width, height: height), cornerRadius: r), with: .color(tint), style: style)
        }
        func line(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat) {
            var p = Path()
            p.move(to: CGPoint(x: c.x + x1, y: c.y + y1))
            p.addLine(to: CGPoint(x: c.x + x2, y: c.y + y2))
            ctx.stroke(p, with: .color(tint), style: style)
        }
        func dot(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat) {
            ctx.fill(Path(ellipseIn: CGRect(x: c.x + x - r, y: c.y + y - r, width: 2 * r, height: 2 * r)), with: .color(tint))
        }
        switch kind {
        case .router:
            rr(-s, -s * 0.1, 2 * s, s * 0.8, s * 0.2)
            line(-s * 0.55, -s * 0.1, -s * 0.8, -s * 0.9)
            line(s * 0.55, -s * 0.1, s * 0.8, -s * 0.9)
            dot(-s * 0.45, s * 0.3, s * 0.1); dot(0, s * 0.3, s * 0.1); dot(s * 0.45, s * 0.3, s * 0.1)
        case .camera:
            rr(-s, -s * 0.5, s * 1.45, s, s * 0.22)
            var p = Path()
            p.move(to: CGPoint(x: c.x + s * 0.45, y: c.y - s * 0.2))
            p.addLine(to: CGPoint(x: c.x + s, y: c.y - s * 0.48))
            p.addLine(to: CGPoint(x: c.x + s, y: c.y + s * 0.48))
            p.addLine(to: CGPoint(x: c.x + s * 0.45, y: c.y + s * 0.2))
            ctx.stroke(p, with: .color(tint), style: style)
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - s * 0.3 - s * 0.26, y: c.y - s * 0.26, width: s * 0.52, height: s * 0.52)), with: .color(tint), style: style)
        case .tv:
            rr(-s, -s * 0.72, 2 * s, s * 1.22, s * 0.14); line(-s * 0.4, s * 0.82, s * 0.4, s * 0.82)
        case .nas:
            rr(-s * 0.75, -s * 0.92, s * 1.5, s * 0.82, s * 0.14); rr(-s * 0.75, s * 0.1, s * 1.5, s * 0.82, s * 0.14)
            dot(s * 0.4, -s * 0.5, s * 0.09); dot(s * 0.4, s * 0.52, s * 0.09)
        case .computer:
            rr(-s * 0.78, -s * 0.78, s * 1.56, s * 1.08, s * 0.1); line(-s * 1.05, s * 0.55, s * 1.05, s * 0.55)
        case .phone:
            rr(-s * 0.5, -s * 0.95, s, s * 1.9, s * 0.22); dot(0, s * 0.7, s * 0.09)
        case .printer:
            rr(-s, -s * 0.3, 2 * s, s * 0.95, s * 0.16); rr(-s * 0.6, -s * 0.95, s * 1.2, s * 0.65, s * 0.08)
            line(-s * 0.5, s * 0.9, s * 0.5, s * 0.9)
        case .iot:
            var p = Path()
            for i in 0..<6 {
                let a = Double.pi / 3 * Double(i) - Double.pi / 6
                let pt = CGPoint(x: c.x + CGFloat(cos(a)) * s, y: c.y + CGFloat(sin(a)) * s)
                if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
            }
            p.closeSubpath()
            ctx.stroke(p, with: .color(tint), style: style)
            dot(0, 0, s * 0.28)
        case .unknown:
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - s * 0.95, y: c.y - s * 0.95, width: s * 1.9, height: s * 1.9)), with: .color(tint), style: style)
        }
    }
}

struct RadarDot {
    let angle: Double
    let radius: Double
    let color: Color
}

func radarDot(_ ip: String) -> RadarDot {
    let o = Int(ip.split(separator: ".").last ?? "0") ?? 0
    return RadarDot(angle: Double(o) * 137.508, radius: 0.3 + Double(o % 7) * 0.09, color: Theme.signal)
}

/// The scan made visible: rings, a sweeping arm, and each device lighting up as it is found.
struct Radar: View {
    let sweeping: Bool
    var dots: [RadarDot] = []

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !sweeping)) { tl in
            Canvas { ctx, size in
                let time = tl.date.timeIntervalSinceReferenceDate
                let sweep = sweeping ? (time * 138).truncatingRemainder(dividingBy: 360) : 300
                let ring = sweeping ? (time / 2.8).truncatingRemainder(dividingBy: 1) : 0.45
                draw(&ctx, size, sweep: sweep, ring: ring)
            }
        }
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize, sweep: Double, ring: Double) {
        let r = min(size.width, size.height) / 2
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let rect = CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)
        ctx.fill(Path(ellipseIn: rect), with: .radialGradient(Gradient(colors: [Theme.navy.opacity(0.34), .clear]), center: c, startRadius: 0, endRadius: r))
        for k in 1...3 {
            let rk = r * CGFloat(k) / 3
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - rk, y: c.y - rk, width: 2 * rk, height: 2 * rk)), with: .color(Theme.navyLite.opacity(0.24)), lineWidth: 1)
        }
        var cross = Path()
        cross.move(to: CGPoint(x: c.x - r, y: c.y)); cross.addLine(to: CGPoint(x: c.x + r, y: c.y))
        cross.move(to: CGPoint(x: c.x, y: c.y - r)); cross.addLine(to: CGPoint(x: c.x, y: c.y + r))
        ctx.stroke(cross, with: .color(Theme.navyLite.opacity(0.12)), lineWidth: 1)
        let pr = r * (0.25 + 0.75 * ring)
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - pr, y: c.y - pr, width: 2 * pr, height: 2 * pr)), with: .color(Theme.signal.opacity(0.42 * (1 - ring))), lineWidth: 1.5)
        if sweeping {
            let stops = [Gradient.Stop(color: .clear, location: 0), Gradient.Stop(color: .clear, location: 0.74), Gradient.Stop(color: Theme.signal.opacity(0.45), location: 1)]
            ctx.fill(Path(ellipseIn: rect), with: .conicGradient(Gradient(stops: stops), center: c, angle: .degrees(sweep)))
            let a = Angle.degrees(sweep).radians
            var arm = Path()
            arm.move(to: c)
            arm.addLine(to: CGPoint(x: c.x + CGFloat(cos(a)) * r, y: c.y + CGFloat(sin(a)) * r))
            ctx.stroke(arm, with: .color(Theme.signal.opacity(0.95)), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        }
        for d in dots {
            let a = Angle.degrees(d.angle).radians
            let p = CGPoint(x: c.x + CGFloat(cos(a)) * r * CGFloat(d.radius), y: c.y + CGFloat(sin(a)) * r * CGFloat(d.radius))
            ctx.fill(Path(ellipseIn: CGRect(x: p.x - 11, y: p.y - 11, width: 22, height: 22)), with: .color(d.color.opacity(0.22)))
            ctx.fill(Path(ellipseIn: CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10)), with: .color(d.color))
        }
        if sweeping {
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - 8, y: c.y - 8, width: 16, height: 16)), with: .color(Theme.navy))
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - 8, y: c.y - 8, width: 16, height: 16)), with: .color(Theme.ink.opacity(0.85)), lineWidth: 1.5)
        }
    }
}

/// The rampart across the status banner: whole when the home is closed to the internet, breached when it is not.
struct WallStrip: View {
    let breached: Bool
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let h = size.height
            let base = h * 0.45
            let mh = h * 0.42
            let mw = h * 0.72
            let gap = h * 0.5
            let y = h - base
            ctx.fill(Path(CGRect(x: 0, y: y, width: size.width, height: base)), with: .color(color))
            var x: CGFloat = 0
            while x < size.width {
                ctx.fill(Path(CGRect(x: x, y: y - mh, width: mw, height: mh + 1)), with: .color(color))
                x += mw + gap
            }
            if breached {
                let gw = h * 1.2
                let bx = size.width / 2 - gw / 2
                ctx.fill(Path(CGRect(x: bx, y: y - mh - 1, width: gw, height: base + mh + 2)), with: .color(Theme.bg))
                ctx.fill(Path(CGRect(x: bx, y: y - mh - 1, width: gw, height: base + mh + 2)), with: .color(Theme.crit.opacity(0.55)))
            }
        }
    }
}

// MARK: - Shared pieces

struct PrimaryButton: View {
    let text: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(Theme.font(17, .semibold)).foregroundStyle(.white)
                .frame(maxWidth: .infinity).frame(height: 56)
                .background(LinearGradient(colors: [Theme.navy, Theme.navyBright], startPoint: .leading, endPoint: .trailing), in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }
}

struct SecondaryButton: View {
    let text: String
    var icon: String? = nil
    var tint: Color = Theme.ink
    var border: Color = Theme.rule
    var fill: Color = Theme.panel
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let i = icon { Image(systemName: i).font(.system(size: 15, weight: .semibold)) }
                Text(text).font(Theme.font(15, .medium))
            }
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity).frame(height: 54)
            .background(fill, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(border, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct Card<Content: View>: View {
    var padding: CGFloat = 14
    var border: Color = Theme.rule
    var fill: Color = Theme.panel
    var radius: CGFloat = 16
    @ViewBuilder let content: () -> Content
    var body: some View {
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: RoundedRectangle(cornerRadius: radius))
            .overlay(RoundedRectangle(cornerRadius: radius).stroke(border, lineWidth: 1))
    }
}

struct SectionTitle: View {
    let text: String
    var count: Int? = nil
    var body: some View {
        HStack(spacing: 8) {
            Text(text).font(Theme.font(17, .bold)).foregroundStyle(Theme.ink)
            if let c = count {
                Text(verbatim: "\(c)").font(Theme.mono(12)).foregroundStyle(Theme.ink2)
                    .padding(.horizontal, 9).padding(.vertical, 2)
                    .background(Theme.panel2, in: Capsule())
            }
            Spacer()
        }
        .padding(.top, 8)
    }
}

struct Mono: View {
    let text: String
    var color: Color = Theme.ink2
    var size: CGFloat = 12.5
    var body: some View { Text(verbatim: ltr(text)).font(Theme.mono(size)).foregroundStyle(color) }
}

struct SevChip: View {
    let label: String
    let color: Color
    var body: some View {
        Text(label).font(Theme.font(11.5, .semibold)).foregroundStyle(color)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(color.opacity(0.18), in: RoundedRectangle(cornerRadius: 7))
    }
}

struct Badge: View {
    let label: String
    let color: Color
    var body: some View {
        Text(label).font(Theme.font(11, .semibold)).foregroundStyle(color)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .overlay(Capsule().stroke(color.opacity(0.6), lineWidth: 1))
    }
}

struct GoldLabel: View {
    let text: String
    var body: some View { Text(text).font(Theme.font(13, .semibold)).foregroundStyle(Theme.signal) }
}

struct CheckRow: View {
    let check: NetCheck
    let ar: Bool
    @Environment(\.openURL) private var openURL
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle().fill(Theme.severity(check.severity)).frame(width: 10, height: 10).padding(.top, 6)
            VStack(alignment: .leading, spacing: 4) {
                Text(ar ? check.titleAr : check.titleEn).font(Theme.font(15, .semibold)).foregroundStyle(Theme.ink)
                Text(ar ? check.detailAr : check.detailEn).font(Theme.font(13)).foregroundStyle(Theme.ink2).lineSpacing(4)
                if let link = check.link, let url = URL(string: link) {
                    Button { openURL(url) } label: {
                        Text(ar ? (check.linkAr ?? "") : (check.linkEn ?? "")).font(Theme.font(13, .medium))
                    }
                    .buttonStyle(.bordered)
                    .padding(.top, 4)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule, lineWidth: 1))
    }
}

struct Note: View {
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "info.circle").foregroundStyle(Theme.navyLite).font(.system(size: 17))
            Text(text).font(Theme.font(13.5)).foregroundStyle(Theme.ink2).lineSpacing(4)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.navy.opacity(0.14), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.navy.opacity(0.35), lineWidth: 1))
    }
}

struct FixBox: View {
    let ar: Bool
    let fix: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle").foregroundStyle(Theme.ok).font(.system(size: 16, weight: .semibold))
                Text(t(ar, "الحل", "How to fix it")).font(Theme.font(14, .semibold)).foregroundStyle(Theme.ink)
            }
            Text(fix).font(Theme.font(15)).foregroundStyle(Theme.ink).lineSpacing(6)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.navy.opacity(0.22), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.navyLite.opacity(0.45), lineWidth: 1))
    }
}

struct DeviceMini: View {
    let device: DeviceInfo
    let ar: Bool
    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle().fill(Theme.panel).frame(width: 34, height: 34)
                Circle().stroke(Theme.severity(device.worst), lineWidth: 1.5).frame(width: 34, height: 34)
                DeviceGlyph(kind: device.kind, tint: Theme.ink).frame(width: 20, height: 20)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(nameOf(device, ar)).font(Theme.font(14, .medium)).foregroundStyle(Theme.ink)
                Mono(text: device.ip)
            }
            Spacer()
        }
        .padding(10)
        .background(Theme.bg2, in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Sheets

struct SheetFrame<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) { content() }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 28)
        }
        .background(Theme.panel.ignoresSafeArea())
        .presentationDragIndicator(.visible)
    }
}

struct FindingSheet: View {
    @EnvironmentObject var coord: ScanCoordinator
    let finding: Finding
    let device: DeviceInfo?
    let ar: Bool

    var body: some View {
        let r = SoorReport.render(finding, coord.knowledge, ar ? .ar : .en)
        let col = Theme.severity(finding.severity)
        SheetFrame {
            HStack { SevChip(label: r.severityLabel, color: col); Spacer(); Mono(text: hostPort(finding)) }
            Text(r.title).font(Theme.font(21, .bold)).foregroundStyle(Theme.ink).lineSpacing(5).padding(.top, 12)
            if let d = device { DeviceMini(device: d, ar: ar).padding(.top, 12) }
            GoldLabel(text: t(ar, "ما الذي يعنيه هذا", "What this means")).padding(.top, 16)
            Text(r.detail).font(Theme.font(15)).foregroundStyle(Theme.ink2).lineSpacing(6).padding(.top, 4)
            if !r.fix.isEmpty { FixBox(ar: ar, fix: r.fix).padding(.top, 16) }
        }
    }
}

struct DeviceSheet: View {
    @EnvironmentObject var coord: ScanCoordinator
    @Environment(\.openURL) private var openURL
    let device: DeviceInfo
    let findings: [Finding]
    let ar: Bool
    let onFinding: (Finding) -> Void

    var body: some View {
        SheetFrame {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(Theme.bg2).frame(width: 58, height: 58)
                    Circle().stroke(Theme.severity(device.worst), lineWidth: 2).frame(width: 58, height: 58)
                    DeviceGlyph(kind: device.kind, tint: Theme.ink).frame(width: 32, height: 32)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(nameOf(device, ar)).font(Theme.font(20, .bold)).foregroundStyle(Theme.ink)
                    if device.name != nil { Text(DeviceKinds.label(device.kind, ar)).font(Theme.font(13)).foregroundStyle(Theme.ink2) }
                    Mono(text: device.ip)
                }
                Spacer()
                if device.isNew { Badge(label: t(ar, "جديد", "New"), color: Theme.signal) }
            }
            if device.isNew {
                Note(text: t(ar, "لم يرَ سُور هذا الجهاز على شبكتك من قبل، فتحقّق أنك تعرفه.", "Soor has not seen this device on your network before, so check that you know it.")).padding(.top, 12)
            }
            if device.isGateway, device.ports.contains(where: { $0 == 80 || $0 == 443 || $0 == 8080 }) {
                SecondaryButton(text: t(ar, "افتح لوحة الراوتر في المتصفح", "Open the router's page in the browser"), icon: "gearshape") {
                    let scheme = (device.ports.contains(443) && !device.ports.contains(80)) ? "https" : "http"
                    if let u = URL(string: "\(scheme)://\(device.ip)/") { openURL(u) }
                }
                .padding(.top, 12)
            }
            GoldLabel(text: t(ar, "المنافذ المفتوحة", "Open ports")).padding(.top, 18)
            if device.ports.isEmpty {
                Text(t(ar, "لم يفتح هذا الجهاز أي منفذ من المنافذ التي يفحصها سُور.", "This device opened none of the ports Soor checks."))
                    .font(Theme.font(14)).foregroundStyle(Theme.ink2).lineSpacing(4).padding(.top, 6)
            }
            ForEach(device.ports, id: \.self) { p in
                let svc = coord.knowledge.services.first { $0.ports.contains(p) }
                HStack(spacing: 0) {
                    Mono(text: "\(p)", color: Theme.ink, size: 14).frame(width: 64, alignment: .leading)
                    Text(svc.map { ar ? $0.name.ar : $0.name.en } ?? portName(p, ar)).font(Theme.font(14)).foregroundStyle(Theme.ink2)
                    Spacer()
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
                .background(Theme.bg2, in: RoundedRectangle(cornerRadius: 12))
                .padding(.top, 6)
            }
            GoldLabel(text: t(ar, "ما وجده سُور في هذا الجهاز", "What Soor found on this device")).padding(.top, 18)
            if findings.isEmpty {
                Text(t(ar, "لا شيء يستحق القلق في هذا الجهاز.", "Nothing to worry about on this device.")).font(Theme.font(14)).foregroundStyle(Theme.ink2).padding(.top, 6)
            }
            ForEach(findings) { f in
                let r = SoorReport.render(f, coord.knowledge, ar ? .ar : .en)
                Button { onFinding(f) } label: {
                    HStack(spacing: 10) {
                        SevChip(label: r.severityLabel, color: Theme.severity(f.severity))
                        Text(r.title).font(Theme.font(14)).foregroundStyle(Theme.ink).multilineTextAlignment(.leading)
                        Spacer()
                    }
                    .padding(12)
                    .background(Theme.bg2, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .padding(.top, 6)
            }
        }
    }
}

struct AboutSheet: View {
    @EnvironmentObject var coord: ScanCoordinator
    @Environment(\.openURL) private var openURL
    let ar: Bool
    @State private var forgotten = false

    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        return b.isEmpty ? v : "\(v) (\(b))"
    }

    var body: some View {
        SheetFrame {
            HStack(spacing: 14) {
                Image("Mark").resizable().frame(width: 52, height: 52).clipShape(RoundedRectangle(cornerRadius: 15))
                VStack(alignment: .leading, spacing: 2) {
                    Text(t(ar, "سُور", "Soor")).font(Theme.font(22, .bold)).foregroundStyle(Theme.ink)
                    Text(t(ar, "فاحص أمان شبكة البيت", "Home network security scanner") + "  ·  " + ltr(version)).font(Theme.font(13)).foregroundStyle(Theme.ink2)
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                GoldLabel(text: t(ar, "من صنع سُور", "Who made Soor"))
                Text(t(ar, "علي العنزي", "Ali AlEnezi")).font(Theme.font(17, .bold)).foregroundStyle(Theme.ink)
                Text(t(ar, "مشروع مفتوح المصدر من الكويت", "An open source project from Kuwait"))
                    .font(Theme.font(13)).foregroundStyle(Theme.ink2).lineSpacing(4)
                Button {
                    if let u = URL(string: "mailto:site@hotmail.com") { openURL(u) }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "envelope").foregroundStyle(Theme.signal).font(.system(size: 15))
                        Text(verbatim: "site@hotmail.com").font(Theme.mono(14)).foregroundStyle(Theme.ink)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 9)
                    .background(Theme.navy.opacity(0.22), in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.bg2, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.rule, lineWidth: 1))
            .padding(.top, 14)
            Text(t(ar, "سُور يفحص شبكة بيتك كما يفحصها مختبِر الاختراق، فيجد كل جهاز متصل بها ويقرأ ما يفتحه من منافذ، ويميّز المكشوف منها إلى الإنترنت من الآمن داخل الشبكة، ثم يرتّب ما وجده بحسب الخطورة مع خطوة إصلاح لكل اكتشاف.",
                   "Soor scans your home network the way a penetration tester would. It finds every connected device and reads the ports it leaves open, tells what is exposed to the internet from what is safe inside, then ranks what it found by severity with a fix for each."))
                .font(Theme.font(15)).foregroundStyle(Theme.ink2).lineSpacing(6).padding(.top, 16)
            AboutBlock(icon: "checkmark.circle", title: t(ar, "لا يجرّب كلمات المرور", "It never tries passwords"),
                       text: t(ar, "سُور أداة دفاعية، فلا يجرّب كلمة مرور على أي جهاز ولا يستغل ثغرة، بل يقرأ ما تعلنه الأجهزة عن نفسها.",
                                  "Soor is defensive. It tries no password on any device and exploits nothing. It reads only what devices announce about themselves."))
            AboutBlock(icon: "lock", title: t(ar, "داخل شبكتك وحدها", "Inside your network only"),
                       text: t(ar, "لا يتصل إلا بعناوين داخل شبكة بيتك، إذ في شيفرته قاعدة ترفض أي عنوان خارجها قبل الاتصال به، ولا يفحص عبر بيانات الجوال أبدًا.",
                                  "It only connects to addresses inside your home network: a rule in its code refuses any other address before connecting, and it never scans over mobile data."))
            AboutBlock(icon: "info.circle", title: t(ar, "لا يجمع بياناتك", "It collects nothing"),
                       text: t(ar, "لا حساب فيه ولا خادم، ولا يحفظ في هاتفك إلا قائمة الأجهزة التي رآها في شبكتك وملخص آخر فحص ليخبرك بما تغيّر، ويمكنك مسحهما من هنا.",
                                  "No account and no server. The only things it keeps on your phone are the list of devices it has seen on your network and a summary of the last scan, to tell you what changed, and you can erase them here."))
            HStack(spacing: 8) {
                Pill(text: t(ar, "الموقع", "Website")) { if let u = URL(string: "https://soor.3li.info/") { openURL(u) } }
                Pill(text: t(ar, "الخصوصية", "Privacy")) { if let u = URL(string: "https://soor.3li.info/privacy.html") { openURL(u) } }
                Pill(text: t(ar, "الشيفرة", "Source")) { if let u = URL(string: "https://github.com/SiteQ8/Soor") { openURL(u) } }
            }
            .padding(.top, 8)
            SecondaryButton(text: forgotten ? t(ar, "مُسحت الأجهزة المحفوظة", "Saved devices erased") : t(ar, "امسح الأجهزة المحفوظة", "Erase saved devices"), icon: "trash") {
                coord.forgetDevices(); forgotten = true
            }
            .padding(.top, 16)
        }
    }
}

struct AboutBlock: View {
    let icon: String
    let title: String
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(Theme.navy.opacity(0.28)).frame(width: 36, height: 36)
                Image(systemName: icon).foregroundStyle(Theme.signal).font(.system(size: 16, weight: .semibold))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Theme.font(15, .semibold)).foregroundStyle(Theme.ink)
                Text(text).font(Theme.font(13.5)).foregroundStyle(Theme.ink2).lineSpacing(4)
            }
        }
        .padding(.vertical, 8)
    }
}

struct Pill: View {
    let text: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(Theme.font(13)).foregroundStyle(Theme.ink2)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .overlay(Capsule().stroke(Theme.rule, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let text: String
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
