# Try ClearLine on a phone

You do **not** need Android Studio, the Android SDK, or a computer build setup. The app owner will provide an APK to install.

**Current status:** `ClearLine-0.1-native-debug.apk` built successfully, all 170 module unit tests passed, and APK signing/native packaging checks passed. Phone behavior has not yet been verified; these are the first test steps, not a claim that the demo has passed them. This is a debug test build, not a Play Store release.

APK SHA-256: `0e1aeaae9577c5d32e7961796192e58617f509b63e192a576ce25fe53b0c70ec`.

## Install

1. Use a Samsung S24 or another 64-bit ARM Android phone. The app requires Android 8.0 or later; S24 is the intended test device, and other phones are unverified.
2. Get the APK directly from the app owner and open it on the phone. If Android asks, allow installation from the browser or file app you used, then return and tap **Install**. Only use the provided, trusted APK.
3. Open **ClearLine**. A local profile named **My check-ins** should appear. No account is needed for the basic test.

## Download the two models

Connect to Wi-Fi. The two downloads total about **775 MB**; leave additional free storage for the app, temporary files, recordings, and saved check-ins.

1. Open **Setup**.
2. Under **Local voice transcription**, tap **Approve model download** and wait for **Installed**.
3. Under **Liquid local agent**, tap **Approve model download** and wait for **Installed**. Download one model at a time.
4. Tap **Load on this phone** for each model in turn and confirm it can reach **Ready**.

Loading one model intentionally unloads the other. Both files should be installed, but both models do not need to show **Ready** simultaneously. The app switches between them during a check-in. Keep the app open while testing. No Liquid API key is needed.

Leave **Enable Nimble public research** and **Enable RawTree exports** off for the first test.

## Make one private, offline check-in

1. After both downloads finish, turn on airplane mode and make sure Wi-Fi is also off.
2. Open **Home → Start a check-in**.
3. Check **I consent to recording this demonstration.** Leave **Allow descriptive measurement export** off.
4. Tap **Start recording** and allow microphone access when Android asks.
5. In a quiet room, speak in English about a harmless topic such as a walk or a meal. Use only your own consenting voice and avoid names, contact details, or private health information. Aim for 30 seconds; recording should stop automatically then. **Stop recording** can end it earlier.
6. Wait for processing, then open **Summary** if it is not already shown. Check the transcript, recording length, word count, recording pace, and amplitude. Pitch and pauses currently show **Unavailable**. A first check-in correctly has insufficient prior history.
7. If a word is wrong, edit **Correct this clip’s words** and tap **Save correction**. Verify the corrected words remain when you reopen the summary from **Home**.

This basic recording/transcription test should work without sponsor keys or networking once the models are installed. If processing pauses, read the displayed message; use **Resume unfinished work** only when ready to continue. Do not treat a visible error or a stalled task as a passed test.

## Optional online sponsor test

Reconnect to the internet only when you want to test these services. Ask the app owner for the appropriate Nimble/RawTree credentials and, for RawTree, the already configured database name. Phone installation does not create a cloud account or database. Keep keys out of messages and screenshots.

- In **Setup → Optional public services**, enter the supplied key and tap **Save key**. Set **RawTree database** if applicable, enable the service you want, then tap **Save service settings**.
- For Nimble, open the check-in’s **Follow-up**, enter the city, review or edit **Exact query to send to Nimble**, check **I approve this exact query and city for Nimble.**, and tap **Find public resources**. Confirm the displayed query and returned sources.
- For RawTree, use **Summary → Your cloud-export choice** to approve the displayed current summary’s selected fields. Any optional snippet/keyword requires its own exact-text review. Then use **Refresh exported history** and **Read query diagnostics**. Empty history or insufficient comparison history is expected before enough approved check-ins exist; counts do not update every ten seconds.

The app owner should follow the native [sponsor integration guide](../sponsors/README.md) and [RawTree account/database contract and verification guide](../sponsors/RAWTREE.md) for cloud configuration and live write/readback checks. The basic phone test does not require this setup.

## Report what happened

Send the app owner the phone model, Android version, provided APK filename, the exact step that failed, the visible error text, whether networking was on, and roughly how long you waited. Include whether each model showed **Installed** or **Ready**.

A screenshot of the error, summary, or diagnostics helps if it contains no private information. **Setup intentionally blocks screenshots** because it contains credential controls; describe its status/error text instead. Never send API keys, recordings, or a private transcript. Keep the installed app and its data until the failure is understood so saved work remains available for diagnosis.
