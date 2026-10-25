# Sete

A watering tracker for the house plants, for a Galaxy Z Flip 7. Italian for thirst.

Each plant's photo holds its water: the level is what is left of its last drink and drains day by day as the next one comes due. Watering drops a single bead into the photo, a ring spreads and the level rises past its mark and settles. Built to Bellezza Necessaria, the same doctrine as Fold Motion and Fold Taskbar.

- **Reminders that work from the lock screen and cover screen.** A morning round at a time you choose, and an evening nudge at 7pm for anything still thirsty. Each thirsty plant arrives as its own notification with **Watered** and **Still damp** buttons, bundled under one summary.
- **The soil decides.** *Still damp* waits two days and learns to wait longer; *Bone dry* waters and comes round sooner. Rhythms stretch automatically through autumn and winter.
- **Twenty plants already in,** photographed on 25 Oct 2026, with what each photo showed, how to water it, its thirst signal and whether it is safe around pets.
- **Adapts to where each plant lives.** Light (measured with the phone's light sensor, or estimated from a photo's exposure), a radiator nearby and the pot each change the rhythm, and each plant's page names every factor. A new photo is compared with the last for leaf colour. Everything is worked out on the phone; nothing is sent anywhere.
- **Recognises new plants.** With an Anthropic API key in the build, a new plant's photo is sent to Claude, which names it, reads its condition, and writes its care and its voice. Wrong guess? Tap another possibility, or type its name and Sete writes its care for that plant.
- **Camera first.** **+ Plant** opens the camera; hold it to add without a photo.
- **The home's palette:** greige walls, iron, oak, brick, sage and copper by day; matte black, brick and Edison amber by night, following the phone's light or dark setting.
- **Optional sharing** between two phones, so a plant watered on one stays quiet on the other.

## Get the APK

1. Open the **Actions** tab. **Build APK** runs on every push.
2. When it goes green, open the run and download **Sete-apk**.
3. Unzip it, copy the `.apk` to the phone and open it. Allow installs from that source when asked.

## First run on the Flip 7

1. Open **Sete**, enter a name for the history and tap **Turn on reminders**.
2. Tap the **Reminders** pill (bottom left) and allow **Exact timing** and **Keep reminders alive**. One UI puts apps to sleep otherwise, and reminders arrive late or not at all.
3. **Send a test reminder** to see it on the cover screen.

## Plant recognition (Claude vision)

1. Sign in at [console.anthropic.com](https://console.anthropic.com), add a payment method, and set a monthly **spend limit** (a few pounds is plenty).
2. **API keys > Create key.** Copy it.
3. In this repository: **Settings > Secrets and variables > Actions > New repository secret**, name `ANTHROPIC_API_KEY`, paste the key.
4. **Actions > Build APK > Run workflow**, and install the new APK.

Each identification sends one photo (about 900 pixels on its long side) and costs roughly a penny or two; that figure is an estimate. The key is built into the APK, so keep the APK to your own phones. Without a key, plants are added by picking their type.

## Sharing between two phones (optional)

Needs a free Firebase project. About ten minutes, once.

1. Go to [console.firebase.google.com](https://console.firebase.google.com), **Create a project** called `sete`. Analytics can stay off.
2. **Build > Authentication > Get started > Sign-in method > Anonymous > Enable.**
3. **Build > Firestore Database > Create database**, any European location, **production mode**. Then **Rules**, replace them with these, and **Publish**:
   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /households/{code}/{document=**} {
         allow read, write: if request.auth != null && code.size() >= 8;
       }
     }
   }
   ```
4. **Project settings (gear) > General > Your apps > Android**. Package name `com.fergusonconsultancy.sete`, register, and skip the download and SDK steps.
5. From the same page copy three values: **Web API key**, **App ID** (`1:…:android:…`) and **Project ID**.
6. In this repository: **Settings > Secrets and variables > Actions > New repository secret**, three times: `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID`.
7. **Actions > Build APK > Run workflow.** Install the new APK on both phones.
8. On one phone: **Reminders pill > Sharing the garden > Start a shared garden.** On the other, enter the 10-letter code and **Join**.

The garden code is the key to the shared garden: anyone with the app and the code could read and change it. Fine for houseplants; don't post it anywhere.

## Limits

- Watering advice and pet notes are general guidance, not a botanist's or a vet's. The Kalanchoes and the cane orchid are best guesses from the photos.
- Without sharing, each phone keeps its own garden.
