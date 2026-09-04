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
    private let documentTitleField = UITextField()
    private let documentBodyView = UITextView()
    private let saveButton = UIButton(configuration: .filled())
    private let searchField = UITextField()
    private let searchButton = UIButton(configuration: .filled())
    private let resultButton = UIButton(configuration: .tinted())
    private let openedBodyLabel = UILabel()
    private let statusLabel = UILabel()

    private var runtime: AppleDocumentJourneyRuntime?
    private var currentResultId: String?

    override func viewDidLoad() {
        super.viewDidLoad()
        configureView()
        initializeRuntime()
    }

    deinit {
        runtime?.closeVault()
    }

    private func configureView() {
        view.backgroundColor = UIColor(red: 244 / 255, green: 247 / 255, blue: 245 / 255, alpha: 1)

        let title = UILabel()
        title.text = "AppFusion"
        title.font = .systemFont(ofSize: 34, weight: .bold)
        title.textColor = brandColor

        let subtitle = UILabel()
        subtitle.text = "Private documents · activity · reminders"
        subtitle.font = .systemFont(ofSize: 15)
        subtitle.textColor = .secondaryLabel

        configureTextField(documentTitleField, placeholder: "Document title", identifier: "document-title")
        documentTitleField.textContentType = .none
        documentTitleField.autocorrectionType = .no

        documentBodyView.backgroundColor = .white
        documentBodyView.layer.cornerRadius = 10
        documentBodyView.font = .systemFont(ofSize: 16)
        documentBodyView.textContainerInset = UIEdgeInsets(top: 12, left: 8, bottom: 12, right: 8)
        documentBodyView.heightAnchor.constraint(equalToConstant: 118).isActive = true
        documentBodyView.accessibilityIdentifier = "document-body"
        documentBodyView.accessibilityLabel = "Private note"
        documentBodyView.inputAccessoryView = keyboardToolbar()

        configureActionButton(saveButton, title: "Encrypt & save", identifier: "save-document")
        saveButton.addTarget(self, action: #selector(saveDocument), for: .touchUpInside)
        saveButton.isEnabled = false

        configureTextField(searchField, placeholder: "Search documents", identifier: "search-query")
        searchField.autocorrectionType = .no
        searchField.returnKeyType = .search

        configureActionButton(searchButton, title: "Search", identifier: "search-documents")
        searchButton.addTarget(self, action: #selector(searchDocuments), for: .touchUpInside)
        searchButton.isEnabled = false

        resultButton.configuration?.title = "Open document"
        resultButton.configuration?.cornerStyle = .medium
        resultButton.accessibilityIdentifier = "search-result-item"
        resultButton.addTarget(self, action: #selector(openSearchResult), for: .touchUpInside)
        resultButton.isHidden = true
        resultButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true

        openedBodyLabel.font = .systemFont(ofSize: 16)
        openedBodyLabel.textColor = .label
        openedBodyLabel.numberOfLines = 0
        openedBodyLabel.backgroundColor = .secondarySystemBackground
        openedBodyLabel.layer.cornerRadius = 10
        openedBodyLabel.layer.masksToBounds = true
        openedBodyLabel.accessibilityIdentifier = "opened-document-body"
        openedBodyLabel.isHidden = true

        statusLabel.text = "Preparing secure workspace…"
        statusLabel.font = .systemFont(ofSize: 14)
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 0
        statusLabel.accessibilityIdentifier = "journey-status"

        let stack = UIStackView(arrangedSubviews: [
            title,
            subtitle,
            section("Create encrypted document"),
            documentTitleField,
            section("Private note"),
            documentBodyView,
            saveButton,
            section("Find your work"),
            searchField,
            searchButton,
            resultButton,
            openedBodyLabel,
            statusLabel,
        ])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scroll = UIScrollView()
        scroll.keyboardDismissMode = .onDrag
        scroll.accessibilityIdentifier = "workspace-scroll"
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        view.addSubview(scroll)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 28),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 22),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -22),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -28),
        ])
    }

    private func initializeRuntime() {
        saveButton.isEnabled = false
        searchButton.isEnabled = false
        status("Verifying encrypted document storage…", color: .secondaryLabel)

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let support = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            ).first!
            let root = support.appendingPathComponent("AppFusion", isDirectory: true)

            do {
                try FileManager.default.createDirectory(
                    at: root,
                    withIntermediateDirectories: true,
                    attributes: nil
                )
            } catch {
                DispatchQueue.main.async {
                    self.status("Secure workspace unavailable: \(error.localizedDescription)", color: .systemRed)
                }
                return
            }

            let runtime = AppleDocumentJourneyRuntime(rootDirectoryPath: root.path)
            let startup = runtime.startVault()

            DispatchQueue.main.async {
                if startup.hasPrefix("OK:") {
                    self.runtime = runtime
                    self.saveButton.isEnabled = true
                    self.searchButton.isEnabled = true
                    let verified = startup.split(separator: ":").dropFirst().first.map(String.init) ?? "0"
                    self.status(
                        "Secure workspace ready · \(verified) verified document\(verified == "1" ? "" : "s")",
                        color: .systemGreen
                    )
                } else {
                    runtime.closeVault()
                    self.status("Secure workspace verification failed", color: .systemRed)
                }
            }
        }
    }

    @objc private func saveDocument() {
        view.endEditing(true)
        guard let runtime else {
            status("Secure workspace is not ready", color: .systemRed)
            return
        }
        let title = documentTitleField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let body = documentBodyView.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, !body.isEmpty else {
            status("Enter both a title and private note", color: .systemOrange)
            return
        }

        let id = UUID().uuidString.lowercased()
        saveButton.isEnabled = false
        status("Encrypting document…", color: .secondaryLabel)

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = runtime.createDocument(
                id: id,
                title: title,
                body: body,
                occurredAtEpochMillis: Int64(Date().timeIntervalSince1970 * 1000)
            )
            DispatchQueue.main.async {
                guard let self else { return }
                self.saveButton.isEnabled = true
                if result.hasPrefix("OK:") {
                    self.status("Saved encrypted document · \(title)", color: .systemGreen)
                } else {
                    self.status("Encrypted save failed", color: .systemRed)
                }
            }
        }
    }

    @objc private func searchDocuments() {
        view.endEditing(true)
        guard let runtime else {
            status("Secure workspace is not ready", color: .systemRed)
            return
        }
        let query = searchField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !query.isEmpty else {
            status("Enter a document search", color: .systemOrange)
            return
        }

        searchButton.isEnabled = false
        resultButton.isHidden = true
        openedBodyLabel.isHidden = true
        currentResultId = nil
        status("Searching verified local documents…", color: .secondaryLabel)

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let id = runtime.searchFirstDocumentId(query: query)
            let title = runtime.searchFirstDocumentTitle(query: query)
            DispatchQueue.main.async {
                guard let self else { return }
                self.searchButton.isEnabled = true
                guard let id else {
                    self.status("No verified document matched that search", color: .secondaryLabel)
                    return
                }
                self.currentResultId = id
                self.resultButton.configuration?.title = "Open \(title ?? query)"
                self.resultButton.isHidden = false
                self.status("Verified local result ready", color: .systemGreen)
            }
        }
    }

    @objc private func openSearchResult() {
        view.endEditing(true)
        guard let runtime, let id = currentResultId else {
            status("Select a verified document first", color: .systemOrange)
            return
        }

        resultButton.isEnabled = false
        status("Authenticating and decrypting…", color: .secondaryLabel)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let body = runtime.readDocument(id: id)
            DispatchQueue.main.async {
                guard let self else { return }
                self.resultButton.isEnabled = true
                guard let body else {
                    self.status("Document could not be authenticated", color: .systemRed)
                    return
                }
                self.openedBodyLabel.text = "  \(body)  "
                self.openedBodyLabel.isHidden = false
                self.status("Authenticated document reopened", color: .systemGreen)
            }
        }
    }

    private func status(_ text: String, color: UIColor) {
        statusLabel.text = text
        statusLabel.textColor = color
    }

    private var brandColor: UIColor {
        UIColor(red: 21 / 255, green: 60 / 255, blue: 53 / 255, alpha: 1)
    }

    private func configureTextField(_ field: UITextField, placeholder: String, identifier: String) {
        field.placeholder = placeholder
        field.backgroundColor = .white
        field.layer.cornerRadius = 10
        field.setLeftPadding(12)
        field.heightAnchor.constraint(equalToConstant: 50).isActive = true
        field.accessibilityIdentifier = identifier
        field.inputAccessoryView = keyboardToolbar()
    }

    private func keyboardToolbar() -> UIToolbar {
        let toolbar = UIToolbar()
        let done = UIBarButtonItem(barButtonSystemItem: .done, target: self, action: #selector(dismissKeyboard))
        done.accessibilityIdentifier = "dismiss-keyboard"
        toolbar.items = [UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil), done]
        toolbar.sizeToFit()
        return toolbar
    }

    @objc private func dismissKeyboard() {
        view.endEditing(true)
    }

    private func section(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 19, weight: .semibold)
        label.textColor = brandColor
        label.setContentHuggingPriority(.required, for: .vertical)
        return label
    }

    private func configureActionButton(_ button: UIButton, title: String, identifier: String) {
        var configuration = UIButton.Configuration.filled()
        configuration.title = title
        configuration.baseBackgroundColor = UIColor(red: 53 / 255, green: 104 / 255, blue: 89 / 255, alpha: 1)
        configuration.cornerStyle = .medium
        button.configuration = configuration
        button.heightAnchor.constraint(equalToConstant: 52).isActive = true
        button.accessibilityIdentifier = identifier
    }
}

private extension UITextField {
    func setLeftPadding(_ amount: CGFloat) {
        let spacer = UIView(frame: CGRect(x: 0, y: 0, width: amount, height: 1))
        leftView = spacer
        leftViewMode = .always
    }
}
