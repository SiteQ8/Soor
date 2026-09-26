import SwiftUI

// A finding as a card in the list.
struct FindingCard: View {
    let finding: Finding
    @EnvironmentObject var app: AppState
    @EnvironmentObject var coord: ScanCoordinator
    @Environment(\.colorScheme) var scheme

    var body: some View {
        let r = SoorReport.render(finding, coord.knowledge, app.lang)
        let c = Theme.severityColor(finding.severity, scheme)
        HStack(spacing: 0) {
            Rectangle().fill(c).frame(width: 3)
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(r.severityLabel)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(c.opacity(0.16))
                        .foregroundStyle(c)
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                    Spacer()
                    Text(verbatim: "\(finding.host):\(finding.port)")
                        .font(.caption.monospaced())
                        .foregroundStyle(Theme.ink2(scheme))
                }
                Text(r.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.ink(scheme))
                Text(r.detail)
                    .font(.footnote)
                    .foregroundStyle(Theme.ink2(scheme))
                    .lineLimit(2)
            }
            .padding(14)
        }
        .background(Theme.panel(scheme))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.rule(scheme)))
    }
}

// The full finding, with the fix.
struct FindingDetailView: View {
    let finding: Finding
    @EnvironmentObject var app: AppState
    @EnvironmentObject var coord: ScanCoordinator
    @Environment(\.colorScheme) var scheme
    @Environment(\.dismiss) var dismiss

    var body: some View {
        let r = SoorReport.render(finding, coord.knowledge, app.lang)
        let c = Theme.severityColor(finding.severity, scheme)
        let ar = app.lang == .ar
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Text(r.severityLabel)
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 9).padding(.vertical, 3)
                            .background(c.opacity(0.16)).foregroundStyle(c)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                        Spacer()
                        Text(verbatim: "\(finding.host):\(finding.port)")
                            .font(.footnote.monospaced())
                            .foregroundStyle(Theme.ink2(scheme))
                    }
                    Text(r.title)
                        .font(.title3.weight(.bold))
                        .foregroundStyle(Theme.ink(scheme))
                    Text(r.detail)
                        .foregroundStyle(Theme.ink(scheme))
                    if !r.fix.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(ar ? "الحل" : "How to fix")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Theme.navy)
                            Text(r.fix).foregroundStyle(Theme.ink(scheme))
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Theme.navy.opacity(0.07))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    Spacer()
                }
                .padding(18)
            }
            .background(Theme.bg(scheme).ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(ar ? "تم" : "Done") { dismiss() }
                }
            }
        }
        .environment(\.layoutDirection, app.layout)
    }
}

// What Soor is and what it does not do.
struct AboutView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.colorScheme) var scheme
    @Environment(\.dismiss) var dismiss
    var ar: Bool { app.lang == .ar }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    LogoMark().frame(width: 60, height: 60)
                    Text(ar ? "عن سُور" : "About Soor")
                        .font(.title2.weight(.bold)).foregroundStyle(Theme.ink(scheme))
                    Text(ar
                         ? "سُور يفحص شبكة بيتك كما يفحصها مختبِر الاختراق، فيجد كل جهاز متصل بها ويقرأ ما يفتحه من منافذ، ويميّز المكشوف منها إلى الإنترنت من الآمن داخل الشبكة، ثم يرتّب ما وجده بحسب الخطورة مع خطوة إصلاح لكل اكتشاف، ومن ذلك التحذير من الكاميرات المكشوفة."
                         : "Soor scans your home network the way a tester would. It finds every connected device and reads the ports it leaves open, tells what is exposed to the internet from what is safe inside, then ranks what it found by severity with a fix for each, cameras among them.")
                        .foregroundStyle(Theme.ink2(scheme))
                    block(ar ? "لا يجرّب كلمات المرور" : "It never tries passwords",
                          ar ? "سُور أداة دفاعية، فلا يجرّب كلمة مرور على أي جهاز ولا يستغل ثغرة، بل يقرأ ما تعلنه الأجهزة عن نفسها فقط."
                             : "Soor is a defensive tool. It tries no password on any device and exploits nothing. It knocks on doors and reads only what devices announce about themselves.")
                    block(ar ? "لا يجمع بياناتك" : "It collects nothing",
                          ar ? "يعمل داخل هاتفك بالكامل، فلا حساب ولا خادم، ولا يخرج بشيء عنك، ومعرفته كلها مشحونة داخله."
                             : "It runs entirely inside your phone. No account, no server, it takes nothing about you out, and all its knowledge is shipped inside it.")
                    block(ar ? "شبكتك وحدها" : "Your network only",
                          ar ? "يفحص الشبكة المتصل بها الهاتف ولا يتجاوز عناوينها الخاصة."
                             : "It scans the network the phone is on and does not go beyond its private addresses.")
                    VStack(alignment: .leading, spacing: 6) {
                        Text(ar ? "من صنع سُور" : "Who made Soor")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.signal)
                        Text(ar ? "علي العنزي" : "Ali AlEnezi")
                            .font(.headline)
                            .foregroundStyle(Theme.ink(scheme))
                        Text(ar ? "مشروع مفتوح المصدر من الكويت، للناس لا للشركات"
                                : "An open source project from Kuwait, for people rather than companies")
                            .font(.footnote)
                            .foregroundStyle(Theme.ink2(scheme))
                        Link("site@hotmail.com", destination: URL(string: "mailto:site@hotmail.com")!)
                            .font(.footnote.monospaced())
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.panel(scheme), in: RoundedRectangle(cornerRadius: 14))
                    Link(ar ? "الشيفرة المصدرية على GitHub" : "Source code on GitHub",
                         destination: URL(string: "https://github.com/SiteQ8/Soor")!)
                        .font(.subheadline).foregroundStyle(Theme.navy)
                }
                .padding(18)
            }
            .background(Theme.bg(scheme).ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button(ar ? "تم" : "Done") { dismiss() } }
            }
        }
        .environment(\.layoutDirection, app.layout)
    }

    private func block(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.headline).foregroundStyle(Theme.ink(scheme))
            Text(body).font(.footnote).foregroundStyle(Theme.ink2(scheme))
        }
    }
}

// System share sheet for the text report.
struct ShareSheet: UIViewControllerRepresentable {
    let text: String
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
