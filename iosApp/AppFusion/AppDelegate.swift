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
        window.rootViewController = AppFusionViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}

final class AppFusionViewController: UIViewController {
    private let statusLabel = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 244/255, green: 247/255, blue: 245/255, alpha: 1)

        let title = UILabel()
        title.text = "AppFusion"
        title.font = .systemFont(ofSize: 34, weight: .bold)
        title.textColor = UIColor(red: 21/255, green: 60/255, blue: 53/255, alpha: 1)

        let subtitle = UILabel()
        subtitle.text = "Private documents · activity · reminders"
        subtitle.font = .systemFont(ofSize: 15)
        subtitle.textColor = .secondaryLabel

        let documentTitle = field("Document title")
        let documentBody = field("Write a private note…", height: 110)

        let save = actionButton("Encrypt & save")
        save.addTarget(self, action: #selector(showShellNotice), for: .touchUpInside)

        let search = field("Search documents")
        let searchButton = actionButton("Search")
        searchButton.addTarget(self, action: #selector(showShellNotice), for: .touchUpInside)

        statusLabel.text = "Verifying secure storage…"
        statusLabel.font = .systemFont(ofSize: 14)
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 0
        statusLabel.accessibilityIdentifier = "secure-storage-status"

        let stack = UIStackView(arrangedSubviews: [
            title, subtitle, section("Create encrypted document"), documentTitle,
            documentBody, save, section("Find your work"), search, searchButton, statusLabel,
        ])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        view.addSubview(scroll)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 28),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 22),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -22),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -28),
        ])

        verifySecureStorage()
    }

    private func verifySecureStorage() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = AppleKeychainHostProbe().runProbe()
            DispatchQueue.main.async {
                self?.statusLabel.text = result == "OK"
                    ? "Secure workspace ready · iOS shell linked to shared core"
                    : "Secure storage check failed: \(result)"
                self?.statusLabel.textColor = result == "OK" ? .systemGreen : .systemRed
            }
        }
    }

    @objc private func showShellNotice() {
        let alert = UIAlertController(
            title: "Delivery slice in progress",
            message: "The iOS installable shell is ready. Encrypted document actions are the next Journey J1 connection gate.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Done", style: .default))
        present(alert, animated: true)
    }

    private func field(_ placeholder: String, height: CGFloat = 50) -> UITextField {
        let field = UITextField()
        field.placeholder = placeholder
        field.backgroundColor = .white
        field.layer.cornerRadius = 10
        field.setLeftPadding(12)
        field.heightAnchor.constraint(equalToConstant: height).isActive = true
        return field
    }

    private func section(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 19, weight: .semibold)
        label.textColor = UIColor(red: 21/255, green: 60/255, blue: 53/255, alpha: 1)
        label.setContentHuggingPriority(.required, for: .vertical)
        return label
    }

    private func actionButton(_ text: String) -> UIButton {
        var configuration = UIButton.Configuration.filled()
        configuration.title = text
        configuration.baseBackgroundColor = UIColor(red: 53/255, green: 104/255, blue: 89/255, alpha: 1)
        configuration.cornerStyle = .medium
        let button = UIButton(configuration: configuration)
        button.heightAnchor.constraint(equalToConstant: 52).isActive = true
        return button
    }
}

private extension UITextField {
    func setLeftPadding(_ amount: CGFloat) {
        let spacer = UIView(frame: CGRect(x: 0, y: 0, width: amount, height: 1))
        leftView = spacer
        leftViewMode = .always
    }
}
