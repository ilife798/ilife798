import SwiftUI
import UserNotifications
import shared

// 前台通知展示代理：willPresent 返回横幅+声音，使运行通知在前台也可见。
// 静态属性强持有，防止 delegate 被释放导致前台横幅不显示。
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationDelegate()

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // 启动时挂载通知 delegate（App init 早于任何 Compose UI 与通知投递）。
        UNUserNotificationCenter.current().delegate = NotificationDelegate.shared
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // 快捷指令 / 外部应用通过 ilife798://… 唤起时，把链接交给 Kotlin 侧解析并派发。
                // Swift 不持有 ViewModel：这里只写 AppRequests 信号，真正的跳转由 Compose 里的
                // AppIntentEffects 消费，与 Android 的 handleIntent 走同一条路径。
                .onOpenURL { url in
                    _ = ShortcutRouter.shared.handleUrl(url: url.absoluteString)
                }
        }
        .onChange(of: scenePhase) { newPhase in
            // iOS 15 兼容：使用单参数 onChange（两参数版本需 iOS 17+）。
            switch newPhase {
            case .active:
                AppLifecycle.shared.notifyResumed()
            case .background:
                AppLifecycle.shared.notifyStopped()
            default:
                break
            }
        }
    }
}
