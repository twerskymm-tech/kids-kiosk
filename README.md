# Kids Home

A locked home screen for the kids' Samsung S22. The phone opens straight to big tiles for the allowed apps
(Greenlight, 24/6, and anything else you tick), two buttons to call a parent over Wi-Fi through Google Voice,
and volume and brightness sliders. Nothing else on the phone can be reached.

- **Parent mode:** tap the top-right corner of the kids' screen 5 times quickly, then enter your PIN.
  Kids mode comes back by itself 15 minutes after your last tap in parent mode.
- **Bedtime lock:** every night between the times you set.
- **Shabbos and Yom Tov lock:** from candle lighting until Havdalah, worked out on the phone for your location
  (no internet needed). Call buttons keep working during every lock.
- **Notifications:** hidden. The notification shade, recent apps and Settings are blocked.

## Download

Every change to this repo builds a new app on GitHub. The newest one is always at
**Releases → latest → `kids-home.apk`** (on the right side of the repo page on GitHub).

## Easiest setup: scan a QR code (no computer)

Needs this repo to be public, because the phone downloads the app from the latest release.

1. Factory reset the phone (recovery mode: plug into a computer by USB, hold Side + Volume Down until it restarts,
   then hold Side + Volume Up until the Samsung logo; pick Wipe data/factory reset).
2. On the very first welcome screen, tap the same empty spot **6 times quickly**. A QR scanner opens
   (connect Wi-Fi if it asks).
3. Scan **Releases → latest → `phone-setup-qr.png`**.
4. Tap Next / Accept on each screen and skip any Google sign-in. The phone opens on the kids' screen and asks for a PIN.
5. Continue from step 7 of the computer setup below.

## One-time setup with a Windows PC (about 20 minutes)

Full lock needs the app to be the phone's "device owner". Android only allows that on a phone that was just
factory reset and has **no accounts signed in yet** (no Google account, no Samsung account).

1. **Back up** anything you need from the S22, then factory reset it:
   Settings → General management → Reset → Factory data reset.
2. In the first-start screens, **connect to Wi-Fi** but **skip** signing in to Google and to Samsung
   (choose "Skip" / "Later" every time).
3. On the phone, turn on USB debugging:
   Settings → About phone → Software information → tap **Build number** 7 times.
   Then Settings → Developer options → turn on **USB debugging**.
4. On the PC:
   - Download Google's free **SDK Platform-Tools for Windows**
     (https://developer.android.com/tools/releases/platform-tools) and unzip it, for example to `C:\platform-tools`.
   - Download `kids-home.apk` from this repo's Releases and put it in the same folder.
   - Plug the phone into the PC with a USB cable. On the phone, tap **Allow** when it asks about USB debugging.
5. In that folder, click the address bar, type `cmd`, press Enter, and run these two commands:

   ```
   adb install kids-home.apk
   adb shell dpm set-device-owner com.kidskiosk.phone/.AdminReceiver
   ```

   The second one should end with `Success: Device owner set`. If it says there are already accounts on the
   device, an account got added in step 2: factory reset again and skip every sign-in.
6. On the phone, press Home and pick **Kids Home**. Set your parent PIN.
7. Open parent mode (5 taps top-right + PIN), tap **Phone settings**, and sign in to your Google account
   (Accounts and backup → Manage accounts → Add account).
8. Back in parent mode, tap **Play Store** and install **Greenlight**, **24/6**, and **Google Voice**.
   Open Google Voice once, sign in, and pick the kids' free Voice number (it verifies with your own phone).
9. In parent mode: tick the kids' apps, type the two call-button names and numbers, tap
   **Use this phone's location** for Shabbos times, choose a background photo, and set bedtime.
10. Turn **USB debugging** back off (Phone settings → Developer options), then tap **Back to kids mode**.

## Updating the app later

Download the newest `kids-home.apk`, turn on USB debugging in parent mode, plug the phone in, and run
`adb install -r kids-home.apk`. Your settings stay.

## Removing the lock

Parent mode → **Remove full lock (before uninstalling)**. After that the app can be uninstalled like any other.

## Remote control from your own phone

The kiosk can be controlled from **https://kids-home-control-4e3b85f5.base44.app** (a Base44 app on the parent's account).

1. On the kids' phone, open parent mode and tap **Connect remote control**. It shows an 8-letter code.
2. On your own phone, open the link above, sign in, and type the code.

From then on the kiosk checks in about once a minute: it reports its settings, chores, ticks and installed apps,
and picks up changes made on the web page (apps, chores, kids' names, call buttons, bedtime, Shabbos lock, unlock for a while).
Changes wait while parent mode is open on the phone. The PIN, photo, location, calling app and pictures stay on the phone only.
