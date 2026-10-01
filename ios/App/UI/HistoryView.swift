import SwiftUI
import TFCore
import UIKit
import UniformTypeIdentifiers

/// Every take, newest first under a header per day, as on Android: its status, search, Copy,
/// Transcribe again and Delete, and Clear all, which asks first. Text nobody confirmed as speech is never shown or searched.
struct HistoryView: View {
    let host: SessionHost
    /// The empty page's Try it: back to the Try tab.
    var onTry: () -> Void = {}
    @State private var records: [TakeRecord] = []
    @State private var query = ""
    @State private var confirmClear = false
    @State private var copied: UUID?
    @State private var problem: String?

    var body: some View {
        NavigationStack {
            Group {
                if records.isEmpty {
                    // Every page scrolls, so Try it stays reachable at the largest text sizes: the GeometryReader's
                    // height keeps this centered when it fits, same as before, and lets it scroll when it doesn't.
                    GeometryReader { proxy in
                        ScrollView {
                            ContentUnavailableView {
                                Label("No takes yet", systemImage: "clock")
                            } description: {
                                Text("Everything you dictate shows up here, so you can copy it or transcribe it again. It never leaves your iPhone.")
                            } actions: {
                                Button("Try it", action: onTry).buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                            }
                            .frame(minHeight: proxy.size.height)
                        }
                    }
                } else {
                    list
                }
            }
            .background(Theme.paper)
            .navigationTitle("History")
            .toolbar {
                if records.contains(where: { !$0.status.isLive }) {
                    Button("Clear all") { confirmClear = true }.accessibilityIdentifier("history.clearAll")
                }
            }
            .alert("Delete all takes?", isPresented: $confirmClear) {
                Button("Delete all", role: .destructive, action: clearAll)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(Self.clearMessage(records.filter { !$0.status.isLive }.count))
            }
            .alert(problem ?? "", isPresented: Binding(get: { problem != nil }, set: { if !$0 { problem = nil } })) {
                Button("OK", role: .cancel) {}
            }
            .task(id: host.historyChanges) { records = (try? host.history.all()) ?? [] }
        }
    }

    private var list: some View {
        let shown = records.filter { Self.matches($0, query) }
        return List {
            if shown.isEmpty {
                ContentUnavailableView("No takes match \"\(query.trimmingCharacters(in: .whitespaces))\".", systemImage: "magnifyingglass")
                    .listRowBackground(Color.clear)
            }
            ForEach(Self.days(shown), id: \.day) { group in
                Section {
                    ForEach(group.takes) { row($0) }
                } header: {
                    Text(Self.dayTitle(group.day, now: Date())).foregroundStyle(Theme.heading)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .searchable(text: $query, prompt: "Search your takes")
        .listRowSpacing(12)
    }

    private func row(_ record: TakeRecord) -> some View {
        let text = Self.shownText(record)
        let status = Group {
            if record.status.isLive {
                HStack { ProgressView().controlSize(.mini); Text(Self.label(for: record.status)) }
            } else {
                Label(Self.label(for: record.status), systemImage: Self.symbol(for: record.status))
            }
        }
        let time = Text(Self.timeLine(record)).font(.caption).foregroundStyle(Theme.inkSoft)
        return VStack(alignment: .leading, spacing: 10) {
            ViewThatFits(in: .horizontal) { // the time goes under the status when large text leaves no room
                HStack(alignment: .firstTextBaseline) { status; Spacer(); time }
                VStack(alignment: .leading, spacing: 4) { status; time }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(record.status == .inserted ? Theme.success : Theme.inkSoft)
            if let shown = text ?? Self.copyText(record).map({ "\($0) (partial)" }) {
                Text(shown).lineLimit(6)
            } else {
                Text("Audio only, no text").italic().foregroundStyle(Theme.inkSoft)
            }
            if record.retranscribed, text != nil {
                Label(Self.retranscribedNote(record.status), systemImage: "arrow.clockwise")
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSoft)
            }
            if !record.status.isLive { actions(record) }
        }
        .padding(.vertical, 6)
        .listRowBackground(Theme.card)
    }

    /// Copy, Transcribe again and Delete in a row, or one under another when large text leaves no room. Text can
    /// always shrink by wrapping, so the row candidate below would read as "fitting" even when it isn't: each
    /// label's own `.fixedSize` reports its true, unwrapped width for that fit check, while the row's Spacer (not
    /// under `.fixedSize` itself) keeps pushing Delete to the far edge instead of collapsing; the stacked candidate
    /// has no Spacer, so nothing but the buttons' own spacing sits between them. `.titleAndIcon` stops a Label's own
    /// accessibility-size behavior (icon above title, taller than either candidate expects) from changing the row's
    /// shape out from under both of them.
    private func actions(_ record: TakeRecord) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 20) {
                actionButtons(record)
                Spacer(minLength: 8)
                deleteButton(record)
            }
            VStack(alignment: .leading, spacing: 14) {
                actionButtons(record)
                deleteButton(record)
            }
        }
        .labelStyle(.titleAndIcon)
        .buttonStyle(.borderless)
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(Theme.primary)
    }

    @ViewBuilder private func actionButtons(_ record: TakeRecord) -> some View {
        if let text = Self.copyText(record) {
            Button { copy(text, record.id) } label: {
                Label(copied == record.id ? "Copied" : "Copy", systemImage: "doc.on.doc")
            }
            .fixedSize(horizontal: true, vertical: false)
        }
        Button { transcribeAgain(record.id) } label: {
            if host.retranscribing.contains(record.id) {
                ProgressView().accessibilityLabel("Transcribing again")
            } else {
                Label("Transcribe again", systemImage: "arrow.clockwise")
            }
        }
        .fixedSize(horizontal: true, vertical: false)
        .disabled(host.retranscribing.contains(record.id))
    }

    private func deleteButton(_ record: TakeRecord) -> some View {
        Button { delete(record.id) } label: { Image(systemName: "trash") }
            .fixedSize(horizontal: true, vertical: false)
            .accessibilityLabel("Delete")
            .disabled(host.retranscribing.contains(record.id))
    }

    /// Copy, for this iPhone only (never the Universal Clipboard), checked by reading it back as on Android.
    private func copy(_ text: String, _ id: UUID) {
        UIPasteboard.general.setItems([[UTType.plainText.identifier: text]], options: [.localOnly: true])
        guard UIPasteboard.general.string == text else {
            problem = "Could not copy."
            return
        }
        copied = id
        UIAccessibility.post(notification: .announcement, argument: "Copied.")
        Task {
            try? await Task.sleep(for: .seconds(2))
            if copied == id { copied = nil }
        }
    }

    private func transcribeAgain(_ id: UUID) {
        Task { if let message = await host.transcribeAgain(id) { problem = message.text } }
    }

    private func delete(_ id: UUID) {
        do { try host.deleteTake(id) } catch { problem = "Could not delete." }
    }

    private func clearAll() {
        do { try host.clearHistory() } catch { problem = "Could not delete." }
    }

    /// The Android app's status words. iOS never says which app a take was typed into, so a typed take says "Typed".
    static func label(for status: TakeStatus) -> String {
        switch status {
        case .recording, .transcribing, .staged, .inserting: "In progress"
        case .inserted: "Typed"
        case .unverified: "Check the field"
        case .notInserted: "Not inserted"
        case .noSpeech: "No speech"
        case .cancelled: "Cancelled"
        case .failed: "Failed"
        case .interrupted: "Interrupted"
        case .needsReview: "May already be in the field"
        }
    }

    static func symbol(for status: TakeStatus) -> String {
        switch status {
        case .inserted: "checkmark.circle.fill"
        case .notInserted: "keyboard"
        case .unverified, .needsReview: "exclamationmark.circle"
        case .noSpeech: "mic.slash"
        case .cancelled: "xmark.circle"
        case .failed, .interrupted: "exclamationmark.triangle"
        case .recording, .transcribing, .staged, .inserting: "ellipsis.circle"
        }
    }

    /// Whether a take's status lets it show or copy text at all (Android's HistoryScreen has the same single
    /// mayShowText): not live, and speech was confirmed (not no speech, not interrupted).
    static func mayShowText(_ record: TakeRecord) -> Bool {
        !record.status.isLive && record.status != .noSpeech && record.status != .interrupted
    }

    /// The text a row shows (Android's rule): a take transcribed again shows its new text; a typed take what was typed;
    /// else its text. None while a take is in progress, nor for one that ended without speech confirmed (no speech,
    /// interrupted).
    static func shownText(_ record: TakeRecord) -> String? {
        guard mayShowText(record) else { return nil }
        if !record.retranscribed, [.inserted, .unverified].contains(record.status),
           let typed = record.insertedText?.trimmingCharacters(in: .whitespacesAndNewlines), !typed.isEmpty {
            return typed
        }
        return record.text
    }

    /// What Copy copies: the shown text, else the part a failed or cancelled take got through.
    static func copyText(_ record: TakeRecord) -> String? {
        guard mayShowText(record) else { return nil }
        return shownText(record) ?? record.partialText.flatMap { $0.allSatisfy(\.isWhitespace) ? nil : $0 }
    }

    /// Search looks only at text a row may show, so an unconfirmed transcript never matches.
    static func matches(_ record: TakeRecord, _ query: String) -> Bool {
        let query = query.trimmingCharacters(in: .whitespaces)
        return query.isEmpty || copyText(record)?.localizedCaseInsensitiveContains(query) == true
    }

    /// Under the text of a take transcribed again: a typed take's status is its own, and this text was never typed.
    static func retranscribedNote(_ status: TakeStatus) -> String {
        status == .notInserted ? "Transcribed again" : "Transcribed again. This text wasn't typed in."
    }

    /// Takes by day, newest first (the list is newest first already).
    static func days(_ records: [TakeRecord], calendar: Calendar = .current) -> [(day: Date, takes: [TakeRecord])] {
        var groups: [(day: Date, takes: [TakeRecord])] = []
        for record in records {
            let day = calendar.startOfDay(for: record.startedAt)
            if groups.last?.day == day { groups[groups.count - 1].takes.append(record) } else { groups.append((day, [record])) }
        }
        return groups
    }

    /// "Today", "Yesterday", else "Fri, Sep 25" (with the year outside this year).
    static func dayTitle(_ day: Date, now: Date, calendar: Calendar = .current) -> String {
        if calendar.isDate(day, inSameDayAs: now) { return "Today" }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(day, inSameDayAs: yesterday) {
            return "Yesterday"
        }
        let style = Date.FormatStyle.dateTime.weekday(.abbreviated).month(.abbreviated).day()
        return calendar.isDate(day, equalTo: now, toGranularity: .year) ? day.formatted(style) : day.formatted(style.year())
    }

    /// "8:12 AM · 0:03": the start time and the length (m:ss, or h:mm:ss from an hour).
    static func timeLine(_ record: TakeRecord) -> String {
        let time = record.startedAt.formatted(date: .omitted, time: .shortened)
        guard record.durationMs > 0 else { return time }
        let seconds = record.durationMs / 1_000
        let length = seconds >= 3_600
            ? String(format: "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
            : String(format: "%d:%02d", seconds / 60, seconds % 60)
        return "\(time) · \(length)"
    }

    /// Clear all's question.
    static func clearMessage(_ count: Int) -> String {
        count == 1
            ? "This deletes 1 take and its recording from this iPhone. It can't be undone."
            : "This deletes \(count) takes and their recordings from this iPhone. It can't be undone."
    }
}
