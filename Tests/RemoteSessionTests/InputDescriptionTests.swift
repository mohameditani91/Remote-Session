import UIKit
import Testing
@testable import RemoteSession

private final class Rows: NSObject, UITableViewDataSource, UICollectionViewDataSource {
    let texts: [String]
    init(_ texts: [String]) { self.texts = texts }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { texts.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "row", for: indexPath)
        cell.textLabel?.text = texts[indexPath.row]
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { texts.count }
    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "item", for: indexPath)
        cell.contentView.subviews.forEach { $0.removeFromSuperview() }
        let label = UILabel(frame: cell.contentView.bounds)
        label.text = texts[indexPath.item]
        cell.contentView.addSubview(label)
        return cell
    }
}

@MainActor
@Suite struct InputDescriptionTests {

    private let scene = CGRect(x: 0, y: 0, width: 1000, height: 800)

    private func window() -> UIWindow {
        let window = UIWindow(frame: scene)
        window.isHidden = false
        return window
    }

    private func describeTap(_ window: UIWindow, at point: CGPoint, responder: UIResponder? = nil) -> String? {
        InputDispatcher(windows: { [window] }, bounds: { [scene] in scene }, firstResponder: { responder })
            .describe(.tap(x: Double(point.x / scene.width), y: Double(point.y / scene.height)))
    }

    @Test func buttonsAreNamedByTheirTitle() {
        let window = window()
        let button = UIButton(type: .system)
        button.setTitle("Save", for: .normal)
        button.frame = CGRect(x: 100, y: 100, width: 200, height: 50)
        window.addSubview(button)

        #expect(describeTap(window, at: CGPoint(x: 150, y: 120)) == "\"Save\" button")
    }

    @Test func fieldsAreNamedByTheirPlaceholderNeverTheirText() {
        let window = window()
        let field = UITextField(frame: CGRect(x: 100, y: 100, width: 300, height: 44))
        field.placeholder = "Email"
        field.text = "sam@example.com"
        window.addSubview(field)

        let tapped = describeTap(window, at: CGPoint(x: 150, y: 120))
        #expect(tapped == "\"Email\" field")
        let typed = InputDispatcher(windows: { [window] }, bounds: { [scene] in scene }, firstResponder: { field }).describe(.text("x"))
        #expect(typed == "\"Email\" field")
        #expect(!(tapped ?? "").contains("sam@"))
    }

    @Test func tableRowsAreNumberedWithoutTheirText() {
        let window = window()
        let rows = Rows(["sam@example.com", "kim@example.com", "lee@example.com"])
        let table = UITableView(frame: scene)
        table.register(UITableViewCell.self, forCellReuseIdentifier: "row")
        table.dataSource = rows
        table.rowHeight = 60
        table.contentInsetAdjustmentBehavior = .never
        window.addSubview(table)
        table.reloadData()
        table.layoutIfNeeded()

        let tapped = describeTap(window, at: CGPoint(x: 200, y: 150))
        #expect(tapped == "row 3")
        withExtendedLifetime(rows) {}
    }

    @Test func padKeysAreNamedButOtherItemsAreNumbered() {
        let window = window()
        let rows = Rows(["7", "Sam Lee"])
        let layout = UICollectionViewFlowLayout()
        layout.itemSize = CGSize(width: 100, height: 100)
        layout.minimumInteritemSpacing = 0
        let collection = UICollectionView(frame: scene, collectionViewLayout: layout)
        collection.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "item")
        collection.dataSource = rows
        collection.contentInsetAdjustmentBehavior = .never
        window.addSubview(collection)
        collection.reloadData()
        collection.layoutIfNeeded()

        #expect(describeTap(window, at: CGPoint(x: 50, y: 50)) == "key \"7\"")
        #expect(describeTap(window, at: CGPoint(x: 150, y: 50)) == "item 2")
        withExtendedLifetime(rows) {}
    }

    @Test func masksHideWhatWasTapped() {
        let window = window()
        let pad = UIView(frame: CGRect(x: 100, y: 100, width: 300, height: 300))
        pad.isRemoteSessionMasked = true
        let key = UIButton(type: .system)
        key.setTitle("4", for: .normal)
        key.frame = CGRect(x: 10, y: 10, width: 80, height: 80)
        pad.addSubview(key)
        window.addSubview(pad)

        #expect(describeTap(window, at: CGPoint(x: 150, y: 150)) == "hidden area")
    }

    @Test func plainLabelsAreNeverRead() {
        let window = window()
        let label = UILabel(frame: CGRect(x: 100, y: 100, width: 300, height: 44))
        label.text = "sam@example.com"
        label.isUserInteractionEnabled = true
        window.addSubview(label)

        #expect(describeTap(window, at: CGPoint(x: 150, y: 120)) == nil)
    }
}
