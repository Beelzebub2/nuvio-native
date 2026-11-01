import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    if url.scheme == "nuvio", url.host == "auth",
                       url.fragment != nil || url.query != nil {
                        StarlitOAuthDeepLinkKt.handleStarlitOAuthDeepLink(url: url.absoluteString)
                    } else {
                        AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
                    }
                }
        }
    }
}
