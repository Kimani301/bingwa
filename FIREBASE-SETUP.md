# Firebase setup for Bn (about 10 minutes, no coding)

## 1. Create the project
1. Go to https://console.firebase.google.com > **Add project** (name it e.g. `bn-shop`). Google Analytics can be off.
2. **Build > Authentication > Get started > Sign-in method > Email/Password > Enable > Save.**
3. **Build > Firestore Database > Create database > Production mode** > choose a location near you > Enable.

## 2. Publish the security rules
1. Open `firestore.rules` from this project and replace `OWNER_EMAIL_HERE` with YOUR email in lower case
   (for example `'boss@gmail.com'`). Only that email can ever become the owner.
2. In Firebase: **Firestore Database > Rules**, paste the whole file, press **Publish**.

## 3. Connect the app
1. **Project settings (gear icon) > General > Your apps > Web (</>)** > register an app (any name).
2. Copy `apiKey` and `projectId` into `app/src/main/assets/firebase-config.js` on GitHub, then commit.
   (The API key is not a secret; the rules above are what protect your data.)
3. Wait for the GitHub build, install the APK.

## 4. First use
1. Open Bn, tap **New shop? Create the owner account**, use the SAME email as in the rules. Do this right away.
2. A **Team** tab appears. Tap **+ Add attendant**, enter name, email and a password, and give them those details.
3. Install the same APK on each attendant's phone and sign in. They get your offers automatically,
   cannot edit offers or see profit or settings like commission, and their sales show up in your Team tab.
4. To remove someone: **Team > Switch off**. Within about a minute they are signed out and cannot sell or see sales.
   Use **Reset password** to email them a reset link.

## Good to know
- Offline phones keep selling and sync when they reconnect. A phone that is offline stays signed in until it reconnects.
- Each phone uses its own SIM and its own phone-unlock PIN (never uploaded).
- Free Firebase plan is enough for a small team; each phone checks in about once a minute.
- Keep your Firebase login private; whoever controls the Firebase project controls all the data.
