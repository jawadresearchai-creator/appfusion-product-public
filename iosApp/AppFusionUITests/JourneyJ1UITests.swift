import XCTest

final class JourneyJ1UITests: XCTestCase {
    private let title = "J1EncryptedNote42"
    private let body = "PrivateBodyAlpha42"

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testEncryptedDocumentSurvivesTerminationSearchAndReopen() throws {
        let app = XCUIApplication()
        app.launch()

        let save = app.buttons["save-document"]
        XCTAssertTrue(save.waitForExistence(timeout: 20))
        waitUntilEnabled(save)

        let titleField = app.textFields["document-title"]
        XCTAssertTrue(titleField.waitForExistence(timeout: 5))
        titleField.tap()
        titleField.typeText(title)

        let bodyView = app.textViews["document-body"]
        XCTAssertTrue(bodyView.waitForExistence(timeout: 5))
        bodyView.tap()
        bodyView.typeText(body)

        tapWhenHittable(save, in: app)
        let status = app.staticTexts["journey-status"]
        waitForLabel(status, containing: "Saved encrypted document")

        app.terminate()
        app.launch()
        let search = app.buttons["search-documents"]
        XCTAssertTrue(search.waitForExistence(timeout: 20))
        waitUntilEnabled(search)

        let query = app.textFields["search-query"]
        XCTAssertTrue(query.waitForExistence(timeout: 5))
        tapWhenHittable(query, in: app)
        query.typeText(title)
        tapWhenHittable(search, in: app)

        let result = app.buttons["search-result-item"]
        XCTAssertTrue(result.waitForExistence(timeout: 15))
        tapWhenHittable(result, in: app)

        let opened = app.staticTexts["opened-document-body"]
        XCTAssertTrue(opened.waitForExistence(timeout: 15))
        XCTAssertTrue(opened.label.contains(body))

        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "J1 encrypted document reopened after relaunch"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func waitUntilEnabled(_ element: XCUIElement, timeout: TimeInterval = 20) {
        let predicate = NSPredicate(format: "enabled == true")
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed)
    }

    private func waitForLabel(
        _ element: XCUIElement,
        containing text: String,
        timeout: TimeInterval = 15
    ) {
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed)
    }

    private func tapWhenHittable(
        _ element: XCUIElement,
        in app: XCUIApplication,
        attempts: Int = 6
    ) {
        for _ in 0..<attempts where !element.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(element.isHittable, "Expected \(element) to become hittable")
        element.tap()
    }
}
