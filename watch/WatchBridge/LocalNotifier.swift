import UserNotifications
import WatchKit

/// 갤럭시에서 받은 카톡 메시지를 워치 알림으로 띄운다. (이 앱의 로컬 알림이다)
final class LocalNotifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = LocalNotifier()

    func setup() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            AppLog.shared.add(granted ? "알림 권한 허용됨" : "알림 권한이 거부됨")
        }
    }

    func post(from: String, room: String, text: String) {
        let content = UNMutableNotificationContent()
        content.title = room.isEmpty ? from : (from.isEmpty ? room : "\(room) · \(from)")
        content.body = text
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                AppLog.shared.add("알림 띄우기 실패: \(error.localizedDescription)")
            }
        }
    }

    // 앱이 화면에 떠 있을 때도 배너와 소리를 보여 준다.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}
