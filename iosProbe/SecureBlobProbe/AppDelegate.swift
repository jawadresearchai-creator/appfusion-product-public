import AppFusionShared
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window

        Self.writeProbeResult("APPFUSION_KEYCHAIN_PROBE=STARTED")
        DispatchQueue.global(qos: .userInitiated).async {
            let result = AppleKeychainHostProbe().runProbe()
            Self.writeProbeResult("APPFUSION_KEYCHAIN_PROBE=\(result)")
        }
        return true
    }

    private static func writeProbeResult(_ result: String) {
        guard let documents = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first else {
            return
        }
        try? FileManager.default.createDirectory(
            at: documents,
            withIntermediateDirectories: true
        )
        try? result.write(
            to: documents.appendingPathComponent("secureblob-probe-result.txt"),
            atomically: true,
            encoding: .utf8
        )
    }
}
