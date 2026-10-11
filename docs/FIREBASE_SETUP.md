# Firebase internet relay setup (optional)

RELAY works fully offline without Firebase. The internet relay is an extra route: when a phone
has internet, it can hand a message to Firebase, and the recipient's phone picks it up whenever
it gets internet. Until you finish this guide, the app shows **"Internet relay not configured"**.

Everything below happens in a web browser. Nothing needs to be installed.
Budget: about 15 minutes. The free Spark plan is enough.

## 1. Create a Firebase project

1. Open <https://console.firebase.google.com> and sign in with a Google account.
2. Click **Add project**, name it (for example `relay-hackathon`), and finish the wizard.
   Google Analytics is not needed; you can turn it off.

## 2. Register the Android app

1. In the project overview, click the **Android** icon ("Add app").
2. **Android package name:** `com.relay.app` (it must match exactly).
3. Nickname: anything. The SHA-1 field is optional for anonymous sign-in; leave it empty.
4. Click **Register app**, then **Download google-services.json**.

## 3. Put google-services.json in the project

Copy the downloaded file to:

```
relay/app/google-services.json
```

(next to `app/build.gradle.kts`). The build detects the file automatically and enables the
Firebase plugin. The file is listed in `.gitignore` so it is not committed by accident.
Every phone must run an APK built **with the same** `google-services.json`.

## 4. Enable Anonymous sign-in

1. Firebase console > **Build > Authentication** > **Get started**.
2. **Sign-in method** tab > **Anonymous** > **Enable** > **Save**.

Each phone signs in anonymously. No accounts, emails or passwords are involved.

## 5. Create the Firestore database

1. Firebase console > **Build > Firestore Database** > **Create database**.
2. Choose a location close to you (it cannot be changed later).
3. Start in **production mode** (locked). Do **not** use test mode: test mode lets anyone on the
   internet read and write your data.

## 6. Apply the security rules

1. Firestore Database > **Rules** tab.
2. Replace everything with the contents of [`firestore.rules`](../firestore.rules) from this repo.
3. Click **Publish**.

What the rules enforce:

* Every request must come from a signed-in Firebase user.
* A device id is owned by the first install that registers it; nobody can hijack it.
* A message can only be read by its uploader and its intended recipient.
* Only the recipient can mark a message delivered, and only the delivery fields can change.
* Message bodies are limited to 2000 characters.

No indexes are required: the app only uses single-field equality queries.

## 7. Rebuild and install

Windows (PowerShell, in the `relay` folder):

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install the new APK on **every** phone. Open RELAY on each phone **once while it has internet**
so it registers its device id (Connectivity & Routes shows "Online, registered as RLY-...").
A phone that never registered cannot be reached through the internet relay.

## 7b. Check the setup without phones (optional)

`scripts/firebase_selftest.py` signs in as three test phones and checks registration, upload,
privacy and delivery confirmation against your rules (19 checks). macOS:

```bash
SSL_CERT_FILE=/etc/ssl/cert.pem python3 scripts/firebase_selftest.py app/google-services.json
```

Each run leaves one delivered test message in `messages/` (the rules do not allow deleting
delivered messages); delete it in the Firestore console if you like.

## 8. Test real delivery (Test E)

1. Phone A and Phone B: open RELAY once with internet so both register.
2. Turn Bluetooth off on both (so the message cannot go directly).
3. Phone A: turn internet off (airplane mode, then keep Wi-Fi and data off). Send a message to
   Phone B. It shows `QUEUED — WAITING FOR CONNECTION`.
4. Phone A: turn internet back on. Within a few seconds the status becomes
   **Uploaded to internet relay, waiting for recipient**. This is NOT delivery yet.
5. Phone B (with internet): the message appears in the Inbox.
6. Phone A: the status becomes **Delivered to intended recipient** only after Phone B stored it
   and confirmed through Firestore.
7. Restart both apps; check there are no duplicates.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Internet relay not configured" | `app/google-services.json` was missing when the APK was built. Add it and rebuild. |
| "sign-in failed ... Is Anonymous sign-in enabled" | Enable Anonymous in Authentication > Sign-in method. |
| "Firestore rules rejected the request" | Publish `firestore.rules` (step 6). |
| "the recipient has not registered" | Open RELAY on the recipient phone once while it has internet. |
| "This device id is registered by another install" | The app was reinstalled and got a new Firebase user. Delete that document under `devices/` in the Firestore console, then reopen RELAY. |
| Stays "Uploaded" forever | The recipient has not been online since. It is delivered when it next has internet with RELAY open. |

## Security notes

* No service-account keys or other privileged secrets are in the app. `google-services.json`
  only identifies the project; access is controlled by Authentication and the rules above.
* Messages are protected in transit by TLS and at rest by Google, but they are **not
  end-to-end encrypted**: project owners can read them in the console.
