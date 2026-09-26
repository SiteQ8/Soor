import SwiftUI

@main
struct SoorApp: App {
    @StateObject private var appState = AppState()
    @StateObject private var coordinator = ScanCoordinator()

    init() { Theme.registerFonts() }

    var body: some Scene {
        WindowGroup {
            ScanView()
                .environmentObject(appState)
                .environmentObject(coordinator)
                .environment(\.layoutDirection, appState.layout)
                .preferredColorScheme(.dark)
                .tint(Theme.navyLite)
        }
    }
}
