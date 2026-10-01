#!/usr/bin/env python3
"""App Store Connect for ThumbFree, through Apple's API: checks the app record, fills the version's listing from
the repository's docs/brand/ios/listing.md and its screenshots from docs/brand/ios/screenshots/, and submits the version
for review.

Standard library only; the API token (ES256) is signed with the openssl command. Needs ~/.appstoreconnect/thumbfree.json:
{"issuer": "<Issuer ID>", "key_id": "<Key ID>", "phone": "+1 ...", "first_name": "...", "last_name": "..."}, and the key in
~/.appstoreconnect/private_keys/AuthKey_<Key ID>.p8. Neither is ever committed. The review notes and the contact email
come from the listing's <!-- review --> block or, when it has none, from the same block in the git-ignored
.superpowers/ios/app-review-notes.md at the repository root.

Usage: tools/asc.py check            the app, its versions and builds (reads only)
       tools/asc.py fill [--dry-run] categories, texts, URLs, age rating, review contact and notes, price (free),
                                     availability (every country), content rights, screenshots
       tools/asc.py submit [build]   attaches the version's newest processed build (or that build number) and
                                     submits it for review, once the privacy and support pages answer
       tools/asc.py new-version 1.0.1  creates the next App Store version; App Store Connect copies the listing
                                     into it, and `fill` then adds What's New
"""
import base64, hashlib, json, re, subprocess, sys, time, urllib.error, urllib.request
from pathlib import Path

BUNDLE = "io.github.kabrapratik28.thumbfree"
ROOT = Path(__file__).resolve().parent.parent
BRAND = ROOT.parent / "docs" / "brand" / "ios"  # the listing and the store images
NOTES = ROOT.parent / ".superpowers" / "ios" / "app-review-notes.md"  # the review notes when the listing has none
CONFIG = Path.home() / ".appstoreconnect" / "thumbfree.json"
API = "https://api.appstoreconnect.apple.com"
SHOT_TYPE = "APP_IPHONE_67"  # the 6.7 and 6.9 inch iPhone slot, which takes 1320 x 2868
DRY = "--dry-run" in sys.argv


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=")


def der_to_raw(der):
    """An ECDSA signature from openssl (DER: SEQUENCE of two INTEGERs) as JWT's 64 bytes, r then s."""
    assert der[0] == 0x30, "not a DER signature"
    i = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    parts = []
    for _ in range(2):
        assert der[i] == 0x02, "not a DER integer"
        n = der[i + 1]
        parts.append(int.from_bytes(der[i + 2:i + 2 + n], "big").to_bytes(32, "big"))
        i += 2 + n
    return b"".join(parts)


def token(config):
    key = Path.home() / ".appstoreconnect" / "private_keys" / f"AuthKey_{config['key_id']}.p8"
    now = int(time.time())
    head = b64(json.dumps({"alg": "ES256", "kid": config["key_id"], "typ": "JWT"}).encode())
    body = b64(json.dumps({"iss": config["issuer"], "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"}).encode())
    der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key)], input=head + b"." + body,
                         capture_output=True, check=True).stdout
    return (head + b"." + body + b"." + b64(der_to_raw(der))).decode()


class Client:
    def __init__(self, config):
        self.config, self.bearer, self.made = config, None, 0

    def call(self, method, path, body=None, allow=()):
        if method != "GET" and DRY:
            print(f"(dry run) {method} {path}", json.dumps(body)[:300] if body else "")
            return {"data": {"id": "dry-run", "attributes": {}}}
        if time.time() - self.made > 900:  # a token lives 20 minutes
            self.bearer, self.made = token(self.config), time.time()
        url = path if path.startswith("http") else API + path
        request = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body else None,
                                         headers={"Authorization": f"Bearer {self.bearer}",
                                                  "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request) as response:
                text = response.read()
        except urllib.error.HTTPError as error:
            if error.code in allow:
                return None
            sys.exit(f"{method} {path}: HTTP {error.code}\n{error.read().decode()[:2000]}")
        return json.loads(text) if text else {}

    def get(self, path):
        return self.call("GET", path)

    def every(self, path):
        """Every item of a list, following the pages."""
        while path:
            page = self.get(path)
            yield from page["data"]
            path = page.get("links", {}).get("next")

    def patch(self, kind, ident, attributes=None, relationships=None):
        data = {"type": kind, "id": ident}
        if attributes:
            data["attributes"] = attributes
        if relationships:
            data["relationships"] = relationships
        return self.call("PATCH", f"/v1/{kind}/{ident}", {"data": data})

    def post(self, path, data, included=None, allow=()):
        reply = self.call("POST", path, {"data": data, **({"included": included} if included else {})}, allow)
        return reply and reply["data"]


def rel(kind, ident):
    return {"data": {"type": kind, "id": ident}}


def listing():
    text = (BRAND / "listing.md").read_text()
    # The review notes and the contact email: from the listing when it has the review block, else from NOTES.
    review = text if "<!-- review -->" in text else NOTES.read_text() if NOTES.exists() else ""
    email = re.search(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", review)

    def cell(label):
        return re.sub(r" \[\d+\]$", "", re.search(rf"^\| {re.escape(label)}[^|]*\| (.+?) \|$", text, re.M).group(1))

    def block(tag, source=text):
        return source.split(f"<!-- {tag} -->\n")[1].split(f"\n<!-- /{tag} -->")[0]

    return {"name": cell("Name"), "subtitle": cell("Subtitle"), "copyright": cell("Copyright"),
            "privacy": cell("Privacy Policy URL"), "support": cell("Support URL"), "marketing": cell("Marketing URL"),
            "promo": block("promo"), "description": block("description"), "keywords": block("keywords"),
            "notes": block("review", review) if "<!-- review -->" in review else None,
            "whatsnew": block("whatsnew") if "<!-- whatsnew -->" in text else None, "email": email and email.group()}


def app_of(client):
    apps = client.get(f"/v1/apps?filter[bundleId]={BUNDLE}")["data"]
    if not apps:
        sys.exit(f"No app record for {BUNDLE} yet: create it in App Store Connect (Apps, +, New App).")
    return apps[0]


def editable_version(client, app):
    for version in client.every(f"/v1/apps/{app}/appStoreVersions?filter[platform]=IOS"):
        attributes = version["attributes"]
        if (attributes.get("appVersionState") or attributes.get("appStoreState")) in ("PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED",
                                                          "METADATA_REJECTED", "INVALID_BINARY"):
            return version
    sys.exit("No version to edit (none is in Prepare for Submission).")


def editable_info(client, app):
    infos = client.get(f"/v1/apps/{app}/appInfos")["data"]
    live = ("READY_FOR_DISTRIBUTION", "READY_FOR_SALE")
    return next((i for i in infos if (i["attributes"].get("state") or i["attributes"].get("appStoreState")) not in live),
                infos[0])


def english(client, path):
    items = client.get(path)["data"]
    return next(i for i in items if i["attributes"]["locale"] == "en-US")


def check(client):
    app = app_of(client)
    print("App:", app["id"], app["attributes"]["name"], app["attributes"].get("primaryLocale"))
    for v in client.every(f"/v1/apps/{app['id']}/appStoreVersions"):
        print("Version:", v["attributes"]["versionString"], v["attributes"].get("appStoreState"),
              v["attributes"].get("releaseType"))
    for b in client.get(f"/v1/builds?filter[app]={app['id']}&sort=-uploadedDate&limit=5")["data"]:
        print("Build:", b["attributes"]["version"], b["attributes"]["processingState"], b["attributes"]["uploadedDate"])


AGE_NONE = ["alcoholTobaccoOrDrugUseOrReferences", "contests", "gamblingSimulated", "gunsOrOtherWeapons",
            "horrorOrFearThemes", "matureOrSuggestiveThemes", "medicalOrTreatmentInformation", "profanityOrCrudeHumor",
            "sexualContentGraphicAndNudity", "sexualContentOrNudity", "violenceCartoonOrFantasy", "violenceRealistic",
            "violenceRealisticProlongedGraphicOrSadistic"]
AGE_NO = ["gambling", "unrestrictedWebAccess", "lootBox", "messagingAndChat", "parentalControls", "ageAssurance",
          "userGeneratedContent", "advertising", "socialMedia", "healthOrWellnessTopics"]


def fill(client):
    config, text = client.config, listing()
    if not (text["notes"] and text["email"]):  # checked before anything in App Store Connect changes
        sys.exit(f"No review notes or no contact email: put the notes between <!-- review --> and <!-- /review --> "
                 f"lines, and the email in the same file, in {BRAND / 'listing.md'} or in {NOTES} (git-ignored).")
    app = app_of(client)["id"]
    client.patch("apps", app, {"contentRightsDeclaration": "USES_THIRD_PARTY_CONTENT"})

    info = editable_info(client, app)["id"]
    client.patch("appInfos", info, relationships={"primaryCategory": rel("appCategories", "PRODUCTIVITY"),
                                                  "secondaryCategory": rel("appCategories", "UTILITIES")})
    loc = english(client, f"/v1/appInfos/{info}/appInfoLocalizations")["id"]
    client.patch("appInfoLocalizations", loc, {"name": text["name"], "subtitle": text["subtitle"],
                                               "privacyPolicyUrl": text["privacy"]})

    # Only the answers this API version knows are sent, so a renamed question fails loudly here, not silently.
    age = client.get(f"/v1/appInfos/{info}/ageRatingDeclaration")["data"]
    known = age["attributes"]
    answers = {k: "NONE" for k in AGE_NONE if k in known} | {k: False for k in AGE_NO if k in known}
    left = [k for k, v in known.items() if v is None and k not in answers and k not in ("kidsAgeBand", "ageRatingOverride",
            "ageRatingOverrideV2", "koreaAgeRatingOverride", "developerAgeRatingInfoUrl",
            "socialMediaAgeRestricted", "gracRatingClassificationNumber")]  # only for social or rated-game apps
    client.patch("ageRatingDeclarations", age["id"], answers)

    current = editable_version(client, app)
    version = current["id"]
    client.patch("appStoreVersions", version, {"copyright": text["copyright"], "releaseType": "AFTER_APPROVAL"})
    vloc = english(client, f"/v1/appStoreVersions/{version}/appStoreVersionLocalizations")["id"]
    texts = {"description": text["description"], "keywords": text["keywords"], "promotionalText": text["promo"],
             "supportUrl": text["support"], "marketingUrl": text["marketing"]}
    if text["whatsnew"] and current["attributes"]["versionString"] != "1.0":  # Apple refuses What's New on a first version
        texts["whatsNew"] = text["whatsnew"]
    client.patch("appStoreVersionLocalizations", vloc, texts)

    review = {"contactFirstName": config["first_name"], "contactLastName": config["last_name"],
              "contactPhone": config["phone"], "contactEmail": text["email"], "demoAccountRequired": False,
              "notes": text["notes"]}
    with_review = client.get(f"/v1/appStoreVersions/{version}?include=appStoreReviewDetail")["data"]
    existing = with_review["relationships"]["appStoreReviewDetail"].get("data")
    if existing:
        client.patch("appStoreReviewDetails", existing["id"], review)
    else:
        client.post("/v1/appStoreReviewDetails", {"type": "appStoreReviewDetails", "attributes": review,
                                                  "relationships": {"appStoreVersion": rel("appStoreVersions", version)}})

    # Availability first, since a price needs it; a rerun finds it made already (409). A new price schedule replaces
    # the old one, so posting it again is safe.
    territories = [t["id"] for t in client.every("/v1/territories?limit=200")]
    client.post("/v2/appAvailabilities", {"type": "appAvailabilities", "attributes": {"availableInNewTerritories": True},
        "relationships": {"app": rel("apps", app), "territoryAvailabilities": {
            "data": [{"type": "territoryAvailabilities", "id": f"${{{t}}}"} for t in territories]}}},
        [{"type": "territoryAvailabilities", "id": f"${{{t}}}", "attributes": {"available": True},
          "relationships": {"territory": rel("territories", t)}} for t in territories], allow=(409,))

    free = next(p["id"] for p in client.every(f"/v1/apps/{app}/appPricePoints?filter[territory]=USA&limit=200")
                if float(p["attributes"]["customerPrice"]) == 0)
    client.post("/v1/appPriceSchedules", {"type": "appPriceSchedules", "relationships": {
        "app": rel("apps", app), "baseTerritory": rel("territories", "USA"),
        "manualPrices": {"data": [{"type": "appPrices", "id": "${free}"}]}}},
        [{"type": "appPrices", "id": "${free}", "attributes": {"startDate": None},
          "relationships": {"appPricePoint": rel("appPricePoints", free)}}])

    screenshots(client, vloc)
    if left:
        sys.exit("Age rating questions this script does not answer (set them in App Store Connect): " + ", ".join(left))
    print("Filled. Still yours in App Store Connect: App Privacy and the EU trader status.")


def screenshots(client, vloc):
    # 1-talk.png, 2-typed.png, ... in the order of their numbers (10- after 9-); the contact sheet is not a frame.
    frames = sorted((BRAND / "screenshots").glob("[0-9]*-*.png"), key=lambda f: int(f.name.split("-")[0]))
    if not 1 <= len(frames) <= 10:
        sys.exit(f"Expected one to ten frames in docs/brand/ios/screenshots (found {len(frames)}), uploaded in "
                 "file-name order (1-talk.png first): run tools/store-frames.py first.")
    sets = client.get(f"/v1/appStoreVersionLocalizations/{vloc}/appScreenshotSets")["data"]
    shot_set = next((s["id"] for s in sets if s["attributes"]["screenshotDisplayType"] == SHOT_TYPE), None)
    if shot_set is None:
        shot_set = client.post("/v1/appScreenshotSets", {"type": "appScreenshotSets",
            "attributes": {"screenshotDisplayType": SHOT_TYPE},
            "relationships": {"appStoreVersionLocalization": rel("appStoreVersionLocalizations", vloc)}})["id"]
    else:  # a rerun replaces the set's screenshots
        for old in client.get(f"/v1/appScreenshotSets/{shot_set}/appScreenshots")["data"]:
            client.call("DELETE", f"/v1/appScreenshots/{old['id']}")
    for frame in frames:
        data = frame.read_bytes()
        shot = client.post("/v1/appScreenshots", {"type": "appScreenshots",
            "attributes": {"fileName": frame.name, "fileSize": len(data)},
            "relationships": {"appScreenshotSet": rel("appScreenshotSets", shot_set)}})
        if DRY:
            continue
        for op in shot["attributes"]["uploadOperations"]:
            part = data[op["offset"]:op["offset"] + op["length"]]
            put = urllib.request.Request(op["url"], data=part, method=op["method"],
                                         headers={h["name"]: h["value"] for h in op["requestHeaders"]})
            urllib.request.urlopen(put).read()
        client.patch("appScreenshots", shot["id"], {"uploaded": True,
                                                    "sourceFileChecksum": hashlib.md5(data).hexdigest()})
        for _ in range(90):
            state = client.get(f"/v1/appScreenshots/{shot['id']}")["data"]["attributes"]["assetDeliveryState"]
            if state["state"] in ("COMPLETE", "FAILED"):
                break
            time.sleep(2)
        if state["state"] != "COMPLETE":
            sys.exit(f"{frame.name}: {state['state']} {state.get('errors')}")
        print("Uploaded", frame.name)


def submit(client, build=None):
    text = listing()
    for url in (text["privacy"], text["support"]):
        try:
            urllib.request.urlopen(url, timeout=20).read()
        except Exception as error:
            sys.exit(f"{url} does not answer ({error}): publish the pages first (tools/publish-store-pages.sh).")
    app = app_of(client)["id"]
    version = editable_version(client, app)
    pinned = f"&filter[version]={build}" if build else ""
    builds = client.get(f"/v1/builds?filter[app]={app}&filter[preReleaseVersion.version]="
                        f"{version['attributes']['versionString']}{pinned}&sort=-uploadedDate&limit=1")["data"]
    if not builds or builds[0]["attributes"]["processingState"] != "VALID":
        sys.exit("No processed build for this version yet: upload it (tools/store-upload.sh) and wait for processing.")
    client.call("PATCH", f"/v1/appStoreVersions/{version['id']}/relationships/build", rel("builds", builds[0]["id"]))
    waiting = client.get(f"/v1/reviewSubmissions?filter[app]={app}&filter[state]=READY_FOR_REVIEW")["data"]
    submission = waiting[0] if waiting else client.post("/v1/reviewSubmissions", {
        "type": "reviewSubmissions", "attributes": {"platform": "IOS"}, "relationships": {"app": rel("apps", app)}})
    if not client.get(f"/v1/reviewSubmissions/{submission['id']}/items")["data"]:
        client.post("/v1/reviewSubmissionItems", {"type": "reviewSubmissionItems", "relationships": {
            "reviewSubmission": rel("reviewSubmissions", submission["id"]),
            "appStoreVersion": rel("appStoreVersions", version["id"])}})
    client.patch("reviewSubmissions", submission["id"], {"submitted": True})
    print("Submitted for review; it goes live by itself once Apple approves it.")


def new_version(client, version_string):
    app = app_of(client)["id"]
    for v in client.every(f"/v1/apps/{app}/appStoreVersions?filter[platform]=IOS"):
        if v["attributes"]["versionString"] == version_string:
            return print("Version", version_string, "already exists:", v["attributes"].get("appVersionState"))
    client.post("/v1/appStoreVersions", {"type": "appStoreVersions", "relationships": {"app": rel("apps", app)},
        "attributes": {"platform": "IOS", "versionString": version_string, "releaseType": "AFTER_APPROVAL"}})
    print("Created version", version_string)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    command = args[0] if args else ""
    if command not in ("check", "fill", "submit", "new-version") or (command == "new-version" and len(args) != 2) \
            or len(args) > 2:
        sys.exit(__doc__)
    if not CONFIG.exists():
        sys.exit(f"Missing {CONFIG}: the Issuer ID, Key ID and App Review contact (see the top of this file).")
    client = Client(json.loads(CONFIG.read_text()))
    if command == "new-version":
        new_version(client, args[1])
    elif command == "submit":
        submit(client, args[1] if len(args) == 2 else None)
    else:
        {"check": check, "fill": fill, "submit": submit}[command](client)
