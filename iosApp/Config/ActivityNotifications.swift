import Foundation
import UserNotifications

/// Activity-notification pushes (likes, comments, followers, payments, logins).
///
/// The backend sends a standard APNs **alert** payload for these (VoIP pushes are
/// reserved for calls per Apple policy), plus the deep-link fields in the custom
/// payload: `targetType` / `targetId` / `notificationId`.
///
/// Wire-up in the app delegate / App struct:
///   UNUserNotificationCenter.current().delegate = TelefamActivityNotifications.shared
///
/// On a tap, a `TelefamNotificationOpen` NotificationCenter event is posted with
/// the target in userInfo — the Kotlin shared layer observes it and navigates to
/// the origin (post, profile, wallet, subscriptions, or the notification centre).
final class TelefamActivityNotifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = TelefamActivityNotifications()

    /// Foreground: still show the banner (the inbox badge updates too).
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .badge])
    }

    /// Tap: redirect to the notification's origin inside the app.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        if (info["kind"] as? String) == "notification" {
            NotificationCenter.default.post(
                name: .init("TelefamNotificationOpen"),
                object: nil,
                userInfo: [
                    "targetType": info["targetType"] as? String ?? "NOTIFICATIONS",
                    "targetId": info["targetId"] as? String ?? "",
                    "notificationId": info["notificationId"] as? String ?? ""
                ]
            )
        }
        completionHandler()
    }
}
