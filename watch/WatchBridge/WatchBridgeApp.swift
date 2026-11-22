import SwiftUI

@main
struct WatchBridgeApp: App {
    @StateObject private var client = BLEClient()
    @StateObject private var keepAlive = KeepAlive()

    init() {
        LocalNotifier.shared.setup()
    }

    var body: some Scene {
        WindowGroup {
            ContentView(client: client, keepAlive: keepAlive)
        }
    }
}
