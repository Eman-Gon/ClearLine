# Try ClearLine on a phone

You do **not** need Android Studio, the Android SDK, or a computer build setup. The app owner will provide an APK to install.

**Current status:** `ClearLine-0.1.1-native-debug.apk` (version code 2) built successfully, all **174** module unit tests passed, and APK signing/native packaging checks passed. Live host probes passed Nimble Search/Extract and isolated synthetic RawTree event/memory checks; see [the live sponsor record](SPONSOR_LIVE_CHECKS.md). Phone behavior has not yet been verified; these are the first test steps, not a claim that the phone demo has passed them. This is a debug test build, not a Play Store release.

Owner's copy: `/Volumes/T7/ClearLine-build/share/ClearLine-0.1.1-native-debug.apk` (25,347,856 bytes).

APK SHA-256: `fb53f42ff1613aff1416e92dd7b7596d7c106ee831da9d48b9a0936f3324dc01`.

## Install

1. Use a Samsung S24 or another 64-bit ARM Android phone. The app requires Android 8.0 or later; S24 is the intended test device, and other phones are unverified.
2. Get the APK directly from the app owner and open it on the phone. If Android asks, allow installation from the browser or file app you used, then return and tap **Install**. Only use the provided, trusted APK.
3. Open **ClearLine**. A local profile named **My check-ins** should appear. No account is needed for the basic test.

**Already installed version 0.1?** Open the new APK and choose **Update**. Version 0.1.1
uses the same `com.clearline.app` package and signing certificate, so the update retains
installed models and saved history. Do not uninstall the app or clear its storage. You
can skip model downloads that already show **Installed**. This update fixes long Nimble
search descriptions and RawTree readback of unavailable measurements.

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

The app owner should follow the native [sponsor integration guide](../sponsors/README.md), [RawTree account/database contract and verification guide](../sponsors/RAWTREE.md), and [completed host sponsor checks](SPONSOR_LIVE_CHECKS.md) for cloud configuration and verification. Host probe credentials and synthetic history do not configure the phone or create its comparison history. The basic phone test does not require this setup.

## Report what happened

Send the app owner the phone model, Android version, provided APK filename, the exact step that failed, the visible error text, whether networking was on, and roughly how long you waited. Include whether each model showed **Installed** or **Ready**.

A screenshot of the error, summary, or diagnostics helps if it contains no private information. **Setup intentionally blocks screenshots** because it contains credential controls; describe its status/error text instead. Never send API keys, recordings, or a private transcript. Keep the installed app and its data until the failure is understood so saved work remains available for diagnosis.
