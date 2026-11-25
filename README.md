# Bn (Android app)

Bn wraps the Bn web app (app/src/main/assets/index.html) in an Android app that can
dial USSD codes, send step-by-step replies, and read payment SMS.

## How the screens are arranged
- **Top bar** (green): airtime balance (tap to refresh) and the Auto-dial icon (gold = on).
- **Home**: Total sales and Profit side by side with Today / All time above them, sales per day, recent activity (shows the client's name, taken from the M-Pesa message or typed on the Sell tab). The Failed card jumps to failed sales.
- **Sell**: tap an offer, type the client's phone number (and optionally their name), tap Sell. The sale is dialed straight away,
  even if Auto-dial is off, and shows under Latest. Attendants can use it too.
- **Sales**: All, **Live** (queued, dialing or waiting for Safaricom), Success, Failed. Failed shows how much is still to give back or retry; retry or mark Refunded from a sale.
- **Offers**: Normal, Advanced and Import, with a count on each. **Import** opens the menu code (default `*444*3*1*n#`), types your own number where `n` is, opens every menu after it, and lists the offers it finds under their menu group with the full code of each (for example `*444*3*1*n*3*2*1#`). It never taps the last Accept. It rescans every 40 minutes when no sale is waiting. Offers are grouped into Active and Off; each is a card with its steps shown as
  chips and Calibrate / Steps / Edit / pause / delete buttons (advanced), or Edit / pause / delete (normal). Calibrate all sits at the top.
  A badge on the tab shows offers that were paused because calibration failed.
- **More** (owner only): **Reports** (paid in vs delivered vs not delivered, profit by offer, share/CSV, daily report by SMS), **Customers** (regulars, bulk offer texts), **Health** (permissions, helper, battery, balance, stuck sales) and **Tools** (alerts, owner PIN, blocked numbers, backup/restore, activity history).
- **Money protection**: dialing is held when airtime cannot cover the next sale; failed sales go to the **Refund** filter in Sales; offers can have active hours (clock button on an offer); a payment for an amount with no live offer gets a text listing the closest offers.
- **Team** (owner only, now inside **More**): attendants and their sales.
- **Settings**, split into four tabs: **General** (account, theme, profit, data), **Dialing** (speed, wait,
  airtime balance, SIMs, calibrate), **Texts** (customer messages), **Phone** (USSD helper, screen off, test SMS, activity log).

## Get the APK and the install page (no computer tools needed)
1. Upload everything in this folder to a GitHub repository (the `.github` folder, the `docs` folder and
   `app/bn-debug.p12` must come too).
2. One-time: repo > **Settings** > **Pages** > under *Build and deployment* set **Source** to **GitHub Actions**.
   (Free GitHub Pages needs a public repository.)
3. Open the **Actions** tab > **Build Bn APK and publish install page** > wait for the green ticks (about 3-5 min).
   (If it did not start, press **Run workflow**.)
4. Your install page is now live at `https://YOUR-USERNAME.github.io/YOUR-REPO/`. Open it on the phone and
   tap **Install Bn for Android**. The page explains the install and the key settings, and every new build
   updates both the page and the APK automatically.

The APK is also kept as a download under the run's **Artifacts** (Bn-apk).
The page itself is `docs/index.html`; edit it on GitHub and commit to change the wording.

Every build is signed with the same key (`app/bn-debug.p12`), so a new APK installs OVER the old
one and keeps your sales, offers, settings and granted permissions. Keep that file in the repo.
(This key is for your own use; do not publish the repo's key for a Play Store app. A public repo also makes
the key public, so only install APKs you built yourself.)

## Releasing an update (users get a prompt inside the app)
1. Edit `app/src/main/assets/index.html` (this is the file the app really shows).
2. In `app/build.gradle` raise **both** `versionCode` (e.g. 4 -> 5) and `versionName` (e.g. "1.3" -> "1.4").
   The prompt only appears when `versionCode` is higher than the one on the phone.
3. Upload to GitHub and wait for the green ticks in **Actions**.
4. When users open Bn (with internet) it shows **Update available**. They tap Update, open the downloaded
   file and tap Install. Their data is kept because every build uses the same signing key.
Phones running a version older than 1.3 do not have the checker yet; install 1.3 once from the install page
and every later update will be offered automatically.

## If your GitHub link shows the wrong thing
The link only shows the install page when GitHub Pages is told to use the build. Check these in order:
1. **Settings > Pages > Source must be "GitHub Actions"** (not "Deploy from a branch"). With "Deploy from a branch"
   GitHub just shows this README or a blank page.
2. The repository must be **public** (free Pages needs that).
3. **Actions** tab: the latest run of *Build Bn APK and publish install page* must have green ticks on BOTH
   `build` and `deploy`. If it is red, open it and read the error. If there is no run at all, the `.github`
   folder was not uploaded (hidden folders are often skipped when dragging files). Upload it again.
4. Upload the **unzipped contents** to the repo root (README.md, app, docs and .github must sit at the top level,
   not inside another folder, and not as a .zip file).
5. After a green run, open `https://YOUR-USERNAME.github.io/YOUR-REPO/` and refresh (hold the reload button, or use a private tab).

## First run on the phone
Bn asks for everything it needs, one step at a time:
1. Phone, SMS and Notifications permissions. If Android shows them greyed out or "Restricted setting":
   Settings > Apps > Bn > top-right menu > **Allow restricted settings**, then reopen Bn.
2. Turn on **Bn** in Settings > Accessibility (needed to read USSD menus and type replies).
3. Battery: choose "Don't optimise".
4. Open Bn: the green top bar should show your airtime balance and the Auto-dial icon.

## Accessibility helper (restart every 20 minutes)
Android only lets an app switch its own accessibility service off and on if it has the
special permission WRITE_SECURE_SETTINGS. It can only be given once, from a computer:

1. On the phone: Settings > About phone > tap Build number 7 times > back > Developer options > turn on **USB debugging**.
2. Connect the phone to a computer (with Android platform-tools / adb installed) and run:

       adb shell pm grant com.bn.dialer android.permission.WRITE_SECURE_SETTINGS

3. Open Bn > Settings > **USSD helper**. It should say Bn restarts the helper every 20 minutes.

After that Bn will:
- switch the helper off and on every 20 minutes (it waits for a sale in progress to finish first),
- switch it back on within 2 minutes if it gets turned off,
- restart it if it is on but not responding.

Without this one-time step Bn cannot do it and instead shows a "Bn helper is off" notification.
The permission stays after app updates (same signing key), but is lost if you uninstall the app.

## Selling advanced offers while the screen is off
Open Bn > Settings > **Screen off**, type your phone PIN or password and tap Save (it is stored
encrypted on the phone). Then, when a payment matches an advanced offer and the phone is locked, Bn:
1. wakes the screen,
2. types the PIN on the lock screen (using the accessibility helper),
3. dials the code and sends each step,
4. locks the phone again about 6 seconds after the last sale (it stays unlocked if more customers are queued).

Use the **Test** button in that section to check it works on your phone: it locks the phone,
then 8 seconds later wakes, unlocks and locks it again.
Works with PIN or password only (not pattern or fingerprint). If the PIN is wrong, Bn tries twice and stops,
so the phone does not get locked out.

## Calibrate advanced offers (test without selling)
Safaricom sometimes changes its USSD menus. If option 2 becomes a different bundle, a customer could get an
offer they did not pay for. **Calibrate** catches this before a customer does.

Offers > Advanced > **Calibrate** (one offer) or **Calibrate all**. Just tap it: Bn dials the code and sends every step by itself,
then shows a **list of the steps it went through** and what Safaricom answered at each one. It **stops before the last option and never sends it**,
so nothing is bought. Bn uses your own number (Settings > Calibrate offers, asked once) where the code has `n`.

What you get:
- A green tick for each step that worked, and a note that the last step was not sent. Tap **Steps** on an offer to see the list again.
- If Safaricom answers with an error, or does not answer, the list stops with a red cross and the reason.
- A failed offer is **paused automatically** (switch off in Settings > Calibrate offers), so payments
  for it fail safely instead of delivering the wrong bundle. Fix the code, then calibrate again.

Calibrate needs the screen on and the Bn USSD helper running.

## Customer messages (automatic SMS)
Bn texts the customer's own number (the one in their payment) by SMS:
- **After a successful sale:** "Your offer of Ksh{amount} has been successfully renewed."
- **When the number was already recommended:** "Hi, the number {phone} has already been recommended today. Kindly send a new number."

Edit both messages in Settings > **Customer messages**. You can use `{amount}`, `{phone}` and `{name}` (the offer name).
Each text can be switched off, and you choose which SIM sends them. Each customer gets each text only once per sale.
Other failures (no reply, menu changed, etc.) do not text the customer. Use **Send me a test** to try both on your own number.
This needs the SMS permission (Bn asks for it; after updating the app it asks once more). Each text uses a little airtime.

## Team logins (optional)
To let attendants sign in with their own accounts while you stay the owner, follow `FIREBASE-SETUP.md`
(fill `app/src/main/assets/firebase-config.js` and publish `firestore.rules`). Until you do, Bn works on one phone with no logins.

## Updating the app's screens
Edit `app/src/main/assets/index.html` on GitHub and commit. A new APK is built automatically.


## Balance, screen off and speed
- **Balance**: after every sale Bn checks the airtime balance by itself (Settings > Dialing > Airtime balance). It wakes the phone if the
  screen is off, and if the reply is missing or unreadable it tries again (up to 2 more times). Unreadable replies are written to the activity log.
- **Screen off**: both normal and advanced offers wake and unlock the phone (needs the PIN saved in Settings > Phone > Screen off), run the code, then lock it again.
- **Speed**: Balanced is now 0.4 s between steps and 0.6 s between customers (Fast 0.15 / 0.25, Careful 1 / 1.6). A normal offer no longer waits for
  Safaricom's confirmation text before the next customer starts; it only waits for the USSD reply.
