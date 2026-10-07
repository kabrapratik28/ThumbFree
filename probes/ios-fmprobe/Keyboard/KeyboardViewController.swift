import UIKit
import FoundationModels
import os

private let probeLog = Logger(subsystem: "fmprobe", category: "keyboard")
private let instructions = "Clean up dictated text. Remove repeats and fillers. Keep only the final version of a self-correction. Write times as digits. Keep every name and place. Return only the text."

/// Probe keyboard: Clean rewrites the text before the cursor with the on-device model from inside the keyboard
/// extension and replaces it; Burst makes five calls in a row to see whether the keyboard gets rate limited.
final class KeyboardViewController: UIInputViewController {
    private let status = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        status.numberOfLines = 6
        status.font = .systemFont(ofSize: 11)
        status.accessibilityIdentifier = "fm.status"
        status.text = "ready: \(String(describing: SystemLanguageModel.default.availability)) fullAccess=\(hasFullAccess)"
        let clean = button("Clean", id: "fm.clean", action: #selector(run))
        let burst = button("Burst", id: "fm.burst", action: #selector(burstRun))
        let row = UIStackView(arrangedSubviews: [clean, burst])
        row.distribution = .fillEqually
        let stack = UIStackView(arrangedSubviews: [status, row])
        stack.axis = .vertical
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            stack.topAnchor.constraint(equalTo: view.topAnchor, constant: 8),
            view.heightAnchor.constraint(equalToConstant: 200),
        ])
    }

    private func button(_ title: String, id: String, action: Selector) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.accessibilityIdentifier = id
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    private func call(_ text: String) async throws -> String {
        let model = SystemLanguageModel(useCase: .general, guardrails: .permissiveContentTransformations)
        return try await LanguageModelSession(model: model, instructions: instructions).respond(to: text).content
    }

    @objc private func run() {
        let raw = textDocumentProxy.documentContextBeforeInput ?? ""
        status.text = "running on \(raw.count) chars"
        Task { @MainActor in
            let t0 = Date()
            do {
                let out = try await call(raw)
                for _ in raw { textDocumentProxy.deleteBackward() }
                textDocumentProxy.insertText(out)
                status.text = "done \(String(format: "%.1f", Date().timeIntervalSince(t0)))s fullAccess=\(hasFullAccess): \(out)"
                textDocumentProxy.insertText(" [kb ok \(String(format: "%.1f", Date().timeIntervalSince(t0)))s fullAccess=\(hasFullAccess)]")
            } catch {
                status.text = "error fullAccess=\(hasFullAccess): \(error)"
                textDocumentProxy.insertText(" [kb error fullAccess=\(hasFullAccess): \(String(describing: error).prefix(300))]")
            }
            probeLog.notice("PROBE-KB \(self.status.text ?? "", privacy: .public)")
        }
    }

    @objc private func burstRun() {
        status.text = "burst running"
        Task { @MainActor in
            var ok = 0, lines: [String] = []
            for i in 0..<5 {
                let t0 = Date()
                do { _ = try await call("yes yes see you at six no seven"); ok += 1; lines.append("\(i) ok \(String(format: "%.1f", Date().timeIntervalSince(t0)))s") }
                catch {
                    if #available(iOS 27.0, *), let e = error as? LanguageModelError, case .rateLimited(let info) = e {
                        lines.append("\(i) RATE LIMITED reset in \(info.resetDate.map { String(format: "%.0fs", $0.timeIntervalSinceNow) } ?? "nil")")
                    } else { lines.append("\(i) \(String(describing: error).prefix(90))") }
                }
            }
            status.text = "burst done \(ok)/5: " + lines.joined(separator: "; ")
            textDocumentProxy.insertText(" [burst \(ok)/5: " + lines.joined(separator: "; ") + "]")
            probeLog.notice("PROBE-KB \(self.status.text ?? "", privacy: .public)")
        }
    }
}
