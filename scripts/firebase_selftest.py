"""
RELAY Firebase self-test: exercises the console setup (Anonymous Auth, Firestore, security rules)
the same way the app does, using the public REST APIs. Prints only PASS/FAIL lines, never tokens.

Usage: python3 -I firebase_selftest.py /path/to/google-services.json
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

cfg = json.load(open(sys.argv[1]))
PROJECT = cfg["project_info"]["project_id"]
KEY = cfg["client"][0]["api_key"][0]["current_key"]
BASE = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents"
results = []


def call(method, url, body=None, token=None, timeout=20):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:200].decode(errors="replace")}


def check(name, ok, detail=""):
    ok = bool(ok)
    results.append(ok)
    print(("PASS  " if ok else "FAIL  ") + name + (f"  ({detail})" if detail and not ok else ""))


def err(body):
    e = body.get("error", {}) if isinstance(body, dict) else {}
    return f"{e.get('status', '')} {e.get('message', '')}"[:160]


def anon():
    s, b = call("POST", f"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={KEY}", {"returnSecureToken": True})
    return s, b.get("idToken"), b.get("localId"), b


def sval(v):
    return {"stringValue": v}


def ival(v):
    return {"integerValue": str(v)}


NULL = {"nullValue": None}

# ---------------------------------------------------------------- auth
s, tok_a, uid_a, b = anon()
check("Anonymous sign-in is enabled (phone A)", s == 200 and tok_a, err(b))
if not tok_a:
    print("\nCannot continue without Anonymous sign-in. Enable it: Authentication > Sign-in method > Anonymous.")
    sys.exit(1)
_, tok_b, uid_b, _ = anon()
_, tok_c, uid_c, _ = anon()

suffix = uuid.uuid4().hex[:6].upper()
dev_a, dev_b = f"RLY-TSTA{suffix}", f"RLY-TSTB{suffix}"
now_ts = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def device_doc(uid, dev, name):
    return {"fields": {"uid": sval(uid), "deviceId": sval(dev), "displayName": sval(name), "updatedAt": {"timestampValue": now_ts}}}


# ---------------------------------------------------------------- device registration
s, b = call("PATCH", f"{BASE}/devices/{dev_a}?currentDocument.exists=false", device_doc(uid_a, dev_a, "Self-test A"), tok_a)
check("Firestore database exists and phone A can register its device id", s == 200, f"{s} {err(b)}")
if s == 404 or "NOT_FOUND" in err(b):
    print("\nFirestore database not found. Create it: Build > Firestore Database > Create database.")
s, b = call("PATCH", f"{BASE}/devices/{dev_b}?currentDocument.exists=false", device_doc(uid_b, dev_b, "Self-test B"), tok_b)
check("Phone B can register its device id", s == 200, f"{s} {err(b)}")

s, b = call("PATCH", f"{BASE}/devices/{dev_a}?updateMask.fieldPaths=uid", {"fields": {"uid": sval(uid_c)}}, tok_c)
check("Outsider C CANNOT take over A's device id", s == 403, f"got {s}")
s, b = call("PATCH", f"{BASE}/devices/{dev_a}?updateMask.fieldPaths=displayName&updateMask.fieldPaths=updatedAt",
            {"fields": {"displayName": sval("Self-test A renamed"), "updatedAt": {"timestampValue": now_ts}}}, tok_a)
check("A can update its own display name", s == 200, f"{s} {err(b)}")

# ---------------------------------------------------------------- upload (same shape as the app)
msg_id = str(uuid.uuid4())
created = int(time.time() * 1000)


def message_doc(mid, uploader_uid, recipient_uid):
    return {"fields": {
        "id": sval(mid), "senderId": sval(dev_a), "senderName": sval("Self-test A"),
        "recipientId": sval(dev_b), "recipientName": sval("Self-test B"),
        "body": sval("RELAY self-test message (safe to delete)"), "type": sval("SOS"), "priority": ival(3),
        "createdAtUtc": ival(created), "expiresAtUtc": ival(created + 3_600_000),
        "location": {"mapValue": {"fields": {"label": sval("Self-test"), "source": sval("MANUAL"), "lat": NULL, "lon": NULL,
                                              "accuracyM": NULL, "capturedAtUtc": NULL}}},
        "hopCount": ival(1), "maxHops": ival(6),
        "route": {"arrayValue": {"values": [{"mapValue": {"fields": {"nodeId": sval(dev_a), "nodeName": sval("Self-test A"),
                                                                      "transport": sval("INTERNET_RELAY"), "atUtc": ival(created)}}}]}},
        "uploaderUid": sval(uploader_uid), "uploaderDeviceId": sval(dev_a), "uploaderName": sval("Self-test A"),
        "recipientUid": sval(recipient_uid), "serverCreatedAt": {"timestampValue": now_ts},
        "deliveredAtUtc": NULL, "deliveredByDeviceId": NULL, "deliveredByName": NULL,
    }}


s, b = call("GET", f"{BASE}/messages/{msg_id}", token=tok_a)
check("Uploader may check 'already uploaded?' on a new id (404, not 403)", s == 404, f"got {s} {err(b)}")
s, b = call("PATCH", f"{BASE}/messages/{msg_id}?currentDocument.exists=false", message_doc(msg_id, uid_a, uid_b), tok_a)
check("Phone A can upload a message for registered phone B", s == 200, f"{s} {err(b)}")
s, b = call("PATCH", f"{BASE}/messages/{msg_id}?currentDocument.exists=false", message_doc(msg_id, uid_a, uid_b), tok_a)
check("Uploading the same id again is refused (idempotent; no duplicate)", s in (400, 409, 403), f"got {s}")

bad_id = str(uuid.uuid4())
s, b = call("PATCH", f"{BASE}/messages/{bad_id}?currentDocument.exists=false", message_doc(bad_id, uid_a, uid_c), tok_a)
check("Upload with a wrong recipient uid is refused", s == 403, f"got {s}")
s, b = call("PATCH", f"{BASE}/messages/{bad_id}?currentDocument.exists=false", message_doc(bad_id, uid_c, uid_b), tok_a)
check("Upload pretending to be another uploader is refused", s == 403, f"got {s}")

# ---------------------------------------------------------------- privacy
s, b = call("GET", f"{BASE}/messages/{msg_id}", token=tok_c)
check("Outsider C CANNOT read the message", s == 403, f"got {s}")
s, b = call("GET", f"{BASE}/messages/{msg_id}")
check("Signed-out request CANNOT read the message", s in (401, 403), f"got {s}")


def query(field, uid, token):
    body = {"structuredQuery": {"from": [{"collectionId": "messages"}],
                                "where": {"fieldFilter": {"field": {"fieldPath": field}, "op": "EQUAL", "value": sval(uid)}}}}
    return call("POST", f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents:runQuery", body, token)


s, b = query("recipientUid", uid_b, tok_b)
found = isinstance(b, list) and any(r.get("document", {}).get("name", "").endswith(msg_id) for r in b)
check("Recipient B's inbox query sees the message", s == 200 and found, f"{s}")
s, b = query("recipientUid", uid_b, tok_c)
check("Outsider C CANNOT query B's inbox", s == 403, f"got {s}")

# ---------------------------------------------------------------- delivery confirmation
s, b = call("PATCH", f"{BASE}/messages/{msg_id}?updateMask.fieldPaths=deliveredAtUtc", {"fields": {"deliveredAtUtc": ival(created + 5000)}}, tok_a)
check("Uploader A CANNOT fake a delivery confirmation", s == 403, f"got {s}")
s, b = call("PATCH", f"{BASE}/messages/{msg_id}?updateMask.fieldPaths=body", {"fields": {"body": sval("tampered")}}, tok_b)
check("Recipient B CANNOT change the message text", s == 403, f"got {s}")
s, b = call("PATCH", f"{BASE}/messages/{msg_id}?updateMask.fieldPaths=deliveredAtUtc&updateMask.fieldPaths=deliveredByDeviceId&updateMask.fieldPaths=deliveredByName",
            {"fields": {"deliveredAtUtc": ival(created + 5000), "deliveredByDeviceId": sval(dev_b), "deliveredByName": sval("Self-test B")}}, tok_b)
check("Recipient B can confirm delivery", s == 200, f"{s} {err(b)}")
s, b = query("uploaderUid", uid_a, tok_a)
delivered = isinstance(b, list) and any(
    r.get("document", {}).get("name", "").endswith(msg_id) and "integerValue" in r["document"]["fields"].get("deliveredAtUtc", {})
    for r in b)
check("Uploader A sees the confirmation (how the sender learns 'Delivered')", s == 200 and delivered, f"{s}")

# ---------------------------------------------------------------- withdraw before delivery
w_id = str(uuid.uuid4())
call("PATCH", f"{BASE}/messages/{w_id}?currentDocument.exists=false", message_doc(w_id, uid_a, uid_b), tok_a)
s, b = call("DELETE", f"{BASE}/messages/{w_id}", token=tok_a)
check("Uploader can withdraw an undelivered message", s == 200, f"{s} {err(b)}")

# ---------------------------------------------------------------- cleanup
call("DELETE", f"{BASE}/devices/{dev_a}", token=tok_a)
call("DELETE", f"{BASE}/devices/{dev_b}", token=tok_b)
for t in (tok_a, tok_b, tok_c):
    call("POST", f"https://identitytoolkit.googleapis.com/v1/accounts:delete?key={KEY}", {"idToken": t})
print(f"\nCleanup: test devices and the 3 test sign-ins deleted. One delivered test message remains "
      f"(rules forbid deleting delivered messages): messages/{msg_id}")
print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
