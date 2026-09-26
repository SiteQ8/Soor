import SwiftUI
import UIKit
import CoreText

// The Soor identity: Kuwait navy with a warm gold signal on a deep field, set in
// Readex Pro, the same typeface as the site and the Android app, with IBM Plex
// Mono for addresses and ports. Both ship in the asset catalog under the SIL
// Open Font License and are registered at launch.

enum Theme {
    static let navy = Color(hex: 0x0033A0)
    static let navyBright = Color(hex: 0x1747B8)
    static let navyLite = Color(hex: 0x4C70BC)
    static let navyDeep = Color(hex: 0x002168)
    static let signal = Color(hex: 0xE8A13A)
    static let bg = Color(hex: 0x05060E)
    static let bg2 = Color(hex: 0x0A1024)
    static let panel = Color(hex: 0x0F1730)
    static let panel2 = Color(hex: 0x16214A)
    static let ink = Color(hex: 0xEAF0FB)
    static let ink2 = Color(hex: 0xA7B4D4)
    static let ink3 = Color(hex: 0x7584AD)
    static let rule = Color(hex: 0x23305A)
    static let ok = Color(hex: 0x52C48D)
    static let crit = Color(hex: 0xFF6B5B)
    static let high = Color(hex: 0xF0904A)
    static let med = Color(hex: 0xE8C34A)
    static let info = Color(hex: 0x6FA8E8)

    static func severity(_ s: Severity?) -> Color {
        guard let s = s else { return ok }
        switch s {
        case .critical: return crit
        case .high: return high
        case .medium: return med
        case .low, .info: return info
        }
    }

    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        let name: String
        if weight == .bold || weight == .heavy || weight == .black { name = "ReadexPro-Bold" }
        else if weight == .semibold { name = "ReadexPro-SemiBold" }
        else if weight == .medium { name = "ReadexPro-Medium" }
        else { name = "ReadexPro-Regular" }
        return .custom(name, size: size)
    }

    static func mono(_ size: CGFloat, bold: Bool = false) -> Font {
        .custom(bold ? "IBMPlexMono-Medium" : "IBMPlexMono-Regular", size: size)
    }

    /// Registers the bundled fonts from the asset catalog once, at launch.
    static func registerFonts() {
        for name in ["ReadexProRegular", "ReadexProMedium", "ReadexProSemiBold", "ReadexProBold", "IBMPlexMonoRegular", "IBMPlexMonoMedium"] {
            guard let asset = NSDataAsset(name: name),
                  let provider = CGDataProvider(data: asset.data as CFData),
                  let font = CGFont(provider) else { continue }
            CTFontManagerRegisterGraphicsFont(font, nil)
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}

// Soor speaks Arabic and English. Apple lets the phone, not the app, choose an
// app's language, so the app follows the phone and its Settings page carries
// the Language option; iOS relaunches the app in the new language.
final class AppState: ObservableObject {
    @Published var lang: Lang

    init() { lang = AppState.systemLanguage() }

    static func systemLanguage() -> Lang {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        return code.hasPrefix("ar") ? .ar : .en
    }

    /// Opens Soor's page in Settings, where iOS shows the Language option.
    func openLanguageSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    var layout: LayoutDirection { lang == .ar ? .rightToLeft : .leftToRight }
}
