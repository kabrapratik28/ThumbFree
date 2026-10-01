import SwiftUI
import TFCore

/// The Dictionary tab, in the Android app's words: names and terms spelled your way. Add and Save
/// are explicit, Paste a list says what it will add and skip before it does, entries show in the order added, a note
/// warns about an entry that is also a common word, and Try a phrase shows what a sentence would become. It has no
/// NavigationStack of its own, so Settings can push it too.
struct DictionaryView: View {
    let dictionary: DictionaryStore
    /// The model's language: without English the Dictionary makes exact matches only.
    let language: String?
    @State private var draft = ""
    @State private var added: String?
    @State private var editing: String?
    @State private var pasting = false
    @State private var phrase = ""

    var body: some View {
        let check = Self.check(draft, in: dictionary.entries)
        List {
            Section {
                Text("Names and words ThumbFree should spell your way: family, friends, colleagues, places, work terms.")
                    .foregroundStyle(Theme.inkSoft)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 0, leading: 4, bottom: 8, trailing: 4))
            }
            Section {
                HStack {
                    TextField("Word or phrase", text: $draft)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .submitLabel(.done)
                        .onSubmit(add)
                        .accessibilityIdentifier("dictionary.field")
                    Button("Add", action: add)
                        .buttonStyle(.borderedProminent)
                        .buttonBorderShape(.capsule)
                        .disabled(check.entry == nil || dictionary.isFull)
                        .accessibilityIdentifier("dictionary.add")
                }
                if let line = check.line ?? added {
                    Text(line)
                        .font(.subheadline)
                        .foregroundStyle(check.isProblem ? Theme.error : Theme.inkSoft)
                        .accessibilityIdentifier("dictionary.message")
                }
                Button("Paste a list") { pasting = true }
                    .disabled(dictionary.isFull)
                    .accessibilityIdentifier("dictionary.paste")
            }
            .listRowBackground(Theme.card)
            if dictionary.entries.isEmpty {
                ContentUnavailableView("No words yet", systemImage: "book.closed",
                                       description: Text("Add the names and terms you say often: Mom's name, your street, your team's product names."))
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(dictionary.entries, id: \.self) { entry in row(entry) }
                } header: {
                    Text(Self.countLabel(dictionary.entries.count)).foregroundStyle(Theme.heading)
                }
                Section {
                    TextField("Sentence", text: $phrase, axis: .vertical)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("dictionary.phrase")
                    if !phrase.allSatisfy(\.isWhitespace) {
                        Text(Self.tryPhrase(phrase, entries: dictionary.entries, language: language))
                            .accessibilityIdentifier("dictionary.phraseResult")
                    }
                } header: {
                    Text("Try a phrase").foregroundStyle(Theme.heading)
                } footer: {
                    Text("Type a sentence to see how your Dictionary changes it. This checks spelling changes, not speech recognition.")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.paper)
        .navigationTitle("Dictionary")
        .listRowSpacing(12)
        .onChange(of: draft) { _, draft in if !draft.isEmpty { added = nil } } // Add empties the field: keep its line
        .sheet(isPresented: $pasting) { PasteListSheet(dictionary: dictionary) { added = $0 } }
        .sheet(item: Binding(get: { editing.map(Editing.init) }, set: { editing = $0?.entry })) { item in
            EditEntrySheet(dictionary: dictionary, entry: item.entry)
        }
    }

    private func row(_ entry: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(entry)
                if let note = CustomWords.riskyNote(entry) {
                    Label(note, systemImage: "info.circle").font(.footnote).foregroundStyle(Theme.inkSoft)
                }
            }
            Spacer()
            Button { editing = entry } label: { Image(systemName: "pencil") }
                .accessibilityLabel("Edit \(entry)")
            Button { dictionary.delete(entry) } label: { Image(systemName: "trash") }
                .accessibilityLabel("Delete \(entry)")
        }
        .buttonStyle(.borderless)
        .listRowBackground(Theme.card)
    }

    private func add() {
        guard let entry = Self.check(draft, in: dictionary.entries).entry, !dictionary.isFull,
              dictionary.add(entry) == nil else { return }
        draft = ""
        added = "Added “\(entry)”."
        UIAccessibility.post(notification: .announcement, argument: added)
    }

    /// What the field under Add says about a draft: the entry it would add, and the line under the field (a problem in
    /// red, or the common-word note).
    static func check(_ draft: String, in entries: [String], editing: String? = nil) -> (entry: String?, line: String?, isProblem: Bool) {
        switch CustomWords.checkEntry(draft, existing: entries, editing: editing) {
        case .success(let entry): (entry, CustomWords.riskyNote(entry), false)
        case .failure(let problem): (nil, problem.message, problem.message != nil)
        }
    }

    /// "1 word", "6 words", and at the limit "500 words, the most it keeps".
    static func countLabel(_ count: Int) -> String {
        let words = count == 1 ? "1 word" : "\(count) words"
        return count >= CustomWords.maxEntries ? "\(words), the most it keeps" : words
    }

    /// Try a phrase: what the Dictionary makes of a sentence.
    static func tryPhrase(_ phrase: String, entries: [String], language: String?) -> String {
        let fixed = CustomWords.correct(phrase, entries: entries, exactOnly: CustomWords.exactOnly(language: language))
        return fixed == phrase ? "No Dictionary changes." : "ThumbFree would type: \(fixed)"
    }

    private struct Editing: Identifiable {
        let entry: String
        var id: String { entry }
    }
}

/// Edit entry: the same field and messages as Add, with Save.
private struct EditEntrySheet: View {
    let dictionary: DictionaryStore
    let entry: String
    @State private var draft: String
    @Environment(\.dismiss) private var dismiss

    init(dictionary: DictionaryStore, entry: String) {
        self.dictionary = dictionary
        self.entry = entry
        _draft = State(initialValue: entry)
    }

    var body: some View {
        let check = DictionaryView.check(draft, in: dictionary.entries, editing: entry)
        NavigationStack {
            Form {
                TextField("Word or phrase", text: $draft)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("dictionary.editField")
                if let line = check.line {
                    Text(line).font(.subheadline).foregroundStyle(check.isProblem ? Theme.error : Theme.inkSoft)
                }
            }
            .navigationTitle("Edit entry")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if dictionary.replace(entry, with: draft) == nil { dismiss() }
                    }
                    .disabled(check.entry == nil)
                    .accessibilityIdentifier("dictionary.save")
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// Paste a list: names split at commas and line breaks, with what will be added and skipped before Add.
private struct PasteListSheet: View {
    let dictionary: DictionaryStore
    let onAdded: (String) -> Void
    @State private var raw = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let preview = CustomWords.addList(raw, to: dictionary.entries)
        NavigationStack {
            Form {
                Section {
                    TextField("Names and terms", text: $raw, axis: .vertical)
                        .lineLimit(4...10)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("dictionary.pasteField")
                } header: {
                    Text("Paste names or terms separated by commas or new lines.").textCase(nil)
                } footer: {
                    if !raw.allSatisfy(\.isWhitespace) {
                        Text(preview.summary).accessibilityIdentifier("dictionary.pasteSummary")
                    }
                }
            }
            .navigationTitle("Add a list")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let count = dictionary.addList(raw).added.count
                        onAdded(count == 1 ? "Added 1 word." : "Added \(count) words.")
                        dismiss()
                    }
                    .disabled(preview.added.isEmpty)
                    .accessibilityIdentifier("dictionary.pasteAdd")
                }
            }
        }
    }
}
