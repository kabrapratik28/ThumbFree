import Foundation
import FoundationModels
import os
import TFCore

/// Clean up in the app: the keyboard asks (a `clean` command) and the app, alive in the background through the live
/// session, runs Apple's on-device model, checks the answer (`CleanupCheck`) and writes it to cleanups.json for the
/// keyboard, which replaces the take. Only the on-device system model, never Private Cloud Compute; greedy decoding;
/// instruction v2 and the style's line as the session's instructions, the take as the prompt. One request at a time.
@MainActor final class CleanUp {
    nonisolated private static let log = Logger(subsystem: Brand.bundleID, category: "cleanup")
    /// A request that takes longer is answered as failed, so one stuck call never holds up the next.
    static let timeout = Duration.seconds(20)

    private let shared: SharedStore
    #if DEBUG
    /// UI tests (Debug builds): `-TFFakeCleanup <text>` answers every request with that text (through the checks), and
    /// Clean up reads as ready, so the keyboard's offer, replacement and Undo run on a Simulator, which has no model.
    var fakeAnswer: String?
    #endif
    private var results: [CleanupResult]
    private var queue: Task<Void, Never>?
    /// The model's availability, read at most every 5 s (`status.json` is written about once a second).
    private var known: (value: CleanupAvailability, at: ContinuousClock.Instant)?

    init(shared: SharedStore) {
        self.shared = shared
        results = (try? shared.cleanups()) ?? []
    }

    /// What the keyboards are told: off by Settings' switch, else Apple Intelligence's state on this iPhone.
    func availability(shown: Bool) -> CleanupAvailability {
        guard shown else { return .off }
        #if DEBUG
        if fakeAnswer != nil { return .ready }
        #endif
        let now = ContinuousClock.now
        if let known, now - known.at < .seconds(5) { return known.value }
        let value = Self.modelAvailability()
        known = (value, now)
        return value
    }

    /// Apple Intelligence's state for the model Clean up uses, in the keyboard's terms.
    static func modelAvailability() -> CleanupAvailability {
        let model = Self.model()
        switch model.availability {
        case .available: return model.supportsLocale() ? .ready : .unsupportedLanguage
        case .unavailable(let reason):
            switch reason {
            case .appleIntelligenceNotEnabled: return .appleIntelligenceOff
            case .deviceNotEligible: return .notEligible
            case .modelNotReady: return .notReady
            @unknown default: return .notReady
            }
        @unknown default: return .notReady
        }
    }

    /// The general on-device model with the guardrails meant for rewriting someone's own text.
    nonisolated static func model() -> SystemLanguageModel {
        SystemLanguageModel(useCase: .general, guardrails: .permissiveContentTransformations)
    }

    /// Tidies the command's text in its style (nil: `defaultStyle`, Settings' "Tap ✨ uses"), after any request still
    /// running, then answers.
    func run(_ command: KeyboardCommand, defaultStyle: CleanupStyle) {
        guard let take = command.text else { return }
        let style = command.style ?? defaultStyle
        let previous = queue
        #if DEBUG
        let fake = fakeAnswer
        #endif
        queue = Task { [weak self] in
            await previous?.value
            let started = ContinuousClock.now
            #if DEBUG
            if let fake {
                try? await Task.sleep(for: .seconds(1))
                let result = Self.result(CleanupCheck.check(take: take, output: fake, style: style), requestID: command.id,
                                         takeID: command.takeID)
                self?.answer(result, ms: (ContinuousClock.now - started) / .milliseconds(1))
                return
            }
            #endif
            let result = await Self.clean(take, style: style, requestID: command.id, takeID: command.takeID)
            self?.answer(result, ms: (ContinuousClock.now - started) / .milliseconds(1))
        }
    }

    /// One call to the model, with the timeout. Logs nothing: the caller logs the outcome without the text.
    nonisolated static func clean(_ take: String, style: CleanupStyle, requestID: UUID, takeID: UUID) async -> CleanupResult {
        let model = Self.model()
        guard case .available = model.availability, model.supportsLocale() else {
            return CleanupResult(requestID: requestID, takeID: takeID, state: .unavailable)
        }
        do {
            let output = try await withThrowingTaskGroup(of: String?.self) { group in
                group.addTask {
                    let session = LanguageModelSession(model: model, instructions: CleanupPrompt.instructions(style))
                    let response = try await session.respond(to: CleanupPrompt.prompt(take: take, style: style),
                                                             options: GenerationOptions(samplingMode: .greedy))
                    return response.content
                }
                group.addTask {
                    try await Task.sleep(for: timeout)
                    return nil
                }
                let first = try await group.next() ?? nil
                group.cancelAll()
                return first
            }
            return result(CleanupCheck.check(take: take, output: output, style: style), requestID: requestID, takeID: takeID)
        } catch {
            let limit = Self.rateLimit(error)
            return CleanupResult(requestID: requestID, takeID: takeID, state: limit.limited ? .paused : .failed,
                                 resetAt: limit.resetAt)
        }
    }

    /// The checks' verdict as the keyboard's answer; a rejection's check is logged (a code, never text).
    nonisolated static func result(_ verdict: CleanupVerdict, requestID: UUID, takeID: UUID) -> CleanupResult {
        switch verdict {
        case .ok(let text): return CleanupResult(requestID: requestID, takeID: takeID, state: .done, text: text)
        case .same: return CleanupResult(requestID: requestID, takeID: takeID, state: .same)
        case .rejected(let check):
            log.notice("Clean up answer rejected: \(check, privacy: .public)")
            return CleanupResult(requestID: requestID, takeID: takeID, state: .failed)
        }
    }

    /// Apple's rate limit for background use: on iOS 27 with when it ends, on iOS 26 without a date.
    nonisolated static func rateLimit(_ error: Error) -> (limited: Bool, resetAt: Date?) {
        if #available(iOS 27.0, *), let error = error as? LanguageModelError, case .rateLimited(let info) = error {
            return (true, info.resetDate)
        }
        if let error = error as? LanguageModelSession.GenerationError, case .rateLimited = error { return (true, nil) }
        return (false, nil)
    }

    /// Keeps the answer with the last ones and tells the keyboards to read them.
    private func answer(_ result: CleanupResult, ms: Double) {
        results = Array((results + [result]).suffix(SharedStore.cleanupsLimit))
        do {
            try shared.write(results)
        } catch {
            let code = error as NSError
            Self.log.error("Clean up answer not written (\(code.domain, privacy: .public) \(code.code, privacy: .public))")
        }
        DarwinObserver.post(DarwinName.status)
        Self.log.notice("Clean up \(result.state.rawValue, privacy: .public) in \(Int(ms), privacy: .public) ms")
    }
}
