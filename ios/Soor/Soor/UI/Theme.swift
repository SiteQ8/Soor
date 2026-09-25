import SwiftUI
import UIKit

// The Soor look: Kuwait navy (#0033A0) with a warm gold signal, on a deep field.
// Colours adapt to light and dark. Severity has its own scale.

enum Theme {
    static let navy      = Color(hex: 0x0033A0)
    static let navyLite  = Color(hex: 0x4C70BC)
    static let navyDeep  = Color(hex: 0x002168)
    static let signal    = Color(hex: 0xE8A13A)

    static func bg(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0x05060E) : Color(hex: 0xEEF2FB)
    }
    static func panel(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0x0F1730) : .white
    }
    static func ink(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0xEAF0FB) : Color(hex: 0x0A1024)
    }
    static func ink2(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0xA7B4D4) : Color(hex: 0x4A5578)
    }
    static func rule(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0x23305A) : Color(hex: 0xD3DBEF)
    }

    static func severityColor(_ s: Severity, _ scheme: ColorScheme) -> Color {
        switch s {
        case .critical: return Color(hex: 0xFF6B5B)
        case .high:     return Color(hex: 0xF0904A)
        case .medium:   return Color(hex: 0xE8C34A)
        case .low:      return Color(hex: 0x6FA8E8)
        case .info:     return Color(hex: 0x6FA8E8)
        }
    }
}

extension Color {
    init(hex: UInt) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xff) / 255,
            green: Double((hex >> 8) & 0xff) / 255,
            blue: Double(hex & 0xff) / 255,
            opacity: 1
        )
    }
}

// The app's language is the one iOS chose for Soor: the language set in
// Settings > Soor > Language, else the first of the phone's own languages that
// Soor speaks, else English. iOS shows that Language option because the app
// carries an Arabic and an English localization. Soor keeps no language setting
// of its own, so the phone's Settings is the one place to change it, and iOS
// relaunches the app in the new language.
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
