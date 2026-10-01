/// Apple's long-press alternatives, read from Apple's keyboard on the iOS 26.5 Simulator into
/// `tools/keyboard/apple-alternates.txt`: for a key, the letters or symbols a long press offers, left to right, and the one
/// under the finger when they open (the key's own character, or .com on the email keyboard's `.` and the web address
/// keyboard's .com).
struct KeyAlternates: Equatable {
    let items: [String]
    let start: Int

    /// What a long press on `key` offers on `layer` of a `kind` field, in capitals when `upper`: nil for a key Apple offers
    /// nothing on, and on the digit pad. A field's own keys (its bottom row) have their own; its other keys are a text
    /// field's.
    static func of(_ key: Key, kind: KeyboardKind, layer: KeyLayer, upper: Bool) -> KeyAlternates? {
        guard !kind.isDigitPad else { return nil }
        let name: String
        switch key {
        case .letter(let c): name = upper ? c.uppercased() : String(c)
        case .symbol(let c): name = String(c)
        case .text(let text): name = text
        default: return nil
        }
        let side = layer == .letters ? "letters" : "numbers"
        return table["\(kind.rawValue)\t\(side)\t\(name)"] ?? table["text\t\(side)\t\(name)"]
    }

    /// `tools/keyboard/apple-alternates.txt` less its header (kind, layer, key, the alternatives, the start), as read; a
    /// test holds the two to each other. After an iOS release, re-read the file with `tools/dump-apple-keyboard.sh` and
    /// copy its lines here.
    static let lines = #"""
    text	letters	w	w ŵ	0
    text	letters	e	ë é e è ê ě ẽ ē ė ę	2
    text	letters	r	r ř	0
    text	letters	t	t ț ť þ	0
    text	letters	y	ÿ ŷ ý y	3
    text	letters	u	ų ů ű ũ ü ú ù u û ǔ ū	7
    text	letters	i	į ı ī ĩ ï í ì i î ǐ	7
    text	letters	o	ő ō õ ø œ ǒ ö ó ò o ô	9
    text	letters	a	a à á â ä ǎ æ ã å ā ă ą	0
    text	letters	s	s ß ş ș ś š	0
    text	letters	d	d ď ð	0
    text	letters	g	g ğ ġ	0
    text	letters	h	h ħ	0
    text	letters	k	ķ k	1
    text	letters	l	ľ ļ ł l	3
    text	letters	z	z ź ž ż	0
    text	letters	c	c ç ć č ċ	0
    text	letters	n	ň ņ ń ñ n	4
    text	letters	W	W Ŵ	0
    text	letters	E	Ë É E È Ê Ě Ẽ Ē Ė Ę	2
    text	letters	R	R Ř	0
    text	letters	T	T Ț Ť Þ	0
    text	letters	Y	Ÿ Ŷ Ý Y	3
    text	letters	U	Ų Ů Ű Ũ Ü Ú Ù U Û Ǔ Ū	7
    text	letters	I	Į İ Ī Ĩ Ï Í Ì I Î Ǐ	7
    text	letters	O	Ő Ō Õ Ø Œ Ǒ Ö Ó Ò O Ô	9
    text	letters	A	A À Á Â Ä Ǎ Æ Ã Å Ā Ă Ą	0
    text	letters	S	S ẞ Ś Š Ş Ș	0
    text	letters	D	D Ď Ð	0
    text	letters	G	G Ğ Ġ	0
    text	letters	H	H Ħ	0
    text	letters	K	Ķ K	1
    text	letters	L	Ľ Ļ Ł L	3
    text	letters	Z	Z Ź Ž Ż	0
    text	letters	C	C Ç Ć Č Ċ	0
    text	letters	N	Ň Ņ Ń Ñ N	4
    text	numbers	0	° 0	1
    text	numbers	-	- – — •	0
    text	numbers	/	/ \	0
    text	numbers	$	₽ ¥ € $ ¢ £ ₩	3
    text	numbers	&	& §	0
    text	numbers	"	« » „ “ ” "	5
    text	numbers	.	. …	0
    text	numbers	?	? ¿	0
    text	numbers	!	! ¡	0
    text	numbers	'	` ‘ ’ '	3
    text	numbers	%	% ‰	0
    text	numbers	=	≈ ≠ =	2
    url	letters	.	. …	0
    url	letters	/	/ \	0
    url	letters	.com	.us .org .edu .net .com	4
    email	letters	.	.us .org .edu .net .com	4
    webSearch	letters	.	.us .edu .net .com .org	3
    """#

    private static let table: [String: KeyAlternates] = Dictionary(lines.split(separator: "\n").compactMap { line in
        let field = line.split(separator: "\t").map(String.init)
        guard field.count == 5, let start = Int(field[4]) else { return nil }
        return ("\(field[0])\t\(field[1])\t\(field[2])", KeyAlternates(items: field[3].split(separator: " ").map(String.init), start: start))
    }, uniquingKeysWith: { first, _ in first })
}
