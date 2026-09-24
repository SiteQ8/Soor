import SwiftUI

@main
struct SoorApp: App {
    @StateObject private var appState = AppState()
    @StateObject private var coordinator = ScanCoordinator()

    var body: some Scene {
        WindowGroup {
            ScanView()
                .environmentObject(appState)
                .environmentObject(coordinator)
                .environment(\.layoutDirection, appState.layout)
                .tint(Theme.navy)
        }
    }
}
