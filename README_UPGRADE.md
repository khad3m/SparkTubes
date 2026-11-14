# SparkTube v1.5.0 — Apple Music UI/UX & Settings Cleanup Guide

Repository: [https://github.com/devfahim00/SparkTube](https://github.com/devfahim00/SparkTube)  
Target Release: **`v1.5.0` (`versionCode = 11`)**

---

## ১. এই আপডেটে কী কী পরিবর্তন করা হয়েছে (What Changed)

### A. Settings / Menu Cleanup (সেটিংস থেকে রিমুভ করা অপশনগুলো)
আপনার নির্দেশনা অনুযায়ী **Menu** ট্যাব এবং অ্যাপের ব্যাকগ্রাউন্ড থেকে নিচের ৪টি অপশন সম্পূর্ণ রিমুভ করা হয়েছে:
1. **Backup & restore** (`rowBackup`, JSON import/export bottom sheet) — রিমুভড
2. **Check for update** (`rowCheckUpdate` এবং `MainActivity.kt` এর `silentlyCheckForUpdate()`) — রিমুভড
3. **Join Telegram** (`rowTelegram` এবং `TELEGRAM_URL`) — রিমুভড
4. **About** (`rowAbout` এবং নিচের ভার্সন ফুটার টেক্সট) — রিমুভড

**এখন Menu ট্যাবে শুধুমাত্র এই ৪টি ক্লিন অপশন থাকবে:**
- **Country / Region** (`@+id/rowCountry`)
- **Settings** (`@+id/rowSettings` — General Settings)
- **Video settings** (`@+id/rowVideoSettings`)
- **Music settings** (`@+id/rowMusicSettings`)

---

### B. Pixel-to-Pixel Apple Music UI & Full Interaction (মিউজিক ইউআই আপগ্রেড)

1. **Apple Music Signature Palette (`colors.xml`)**:
   - আগের Spotify Green (`#1DB954`) পরিবর্তন করে Apple Music-এর আসল **Crimson Red/Pink (`#FA243C` / `#FF375F`)** এবং গ্লাসমরফিজম সারফেস (`#24FFFFFF` / `#38FFFFFF`) যুক্ত করা হয়েছে।

2. **Apple Music "Listen Now / Home" Tab (`fragment_music.xml` & `MusicFragment.kt`)**:
   - **Header & Search**: উপরে `APPLE MUSIC · LOSSLESS` ব্যাজ, বড় `34sp` বোল্ড হেডার, ডানপাশে কুইক-প্লে নোট ব্যাজ এবং iOS স্টাইল সার্চ বার (`Search songs, artists...`)।
   - **Quick Library Pills**: `Favorites` এবং `Downloads` বাটনগুলোতে Apple Music-এর লাল আইকন এবং ডানপাশে শেভরন (`›`) দেওয়া হয়েছে।
   - **Top Picks for You (Editorial Hero Carousel)**: Apple Music-এর মতো বড় লম্বা কার্ড (`236dp x 286dp`), যার উপরে `MADE FOR YOU`, `NEW RELEASE`, `SPATIAL AUDIO`, `PERSONAL STATION` লেবেল এবং নিচে গ্লাস-ব্লার ফুটারে গানের নাম ও আর্টিস্ট দেওয়া আছে।
   - **Trending Now Shelf**: `156dp` রাউন্ডেড (`12dp` কর্নার) অ্যালবাম আর্ট কার্ড ও নিচে প্লে ব্যাজ।
   - **Top Songs Ranked Chart**: `1, 2, 3...` র‍্যাংকিং নাম্বারসহ গানের লিস্ট, `Lossless` সাবটাইটেল এবং লং-প্রেসে **Apple Music Context Action Sheet** (`▶ Play Now`, `((•)) Create Station`, `★ Favorite Song`)।
   - **Browse by Category**: Apple Music Spatial Audio ও পপুলার জনরার ডুয়াল-গ্র্যাডিয়েন্ট কার্ড।

3. **Apple Music Full-Screen Now Playing Sheet (`activity_now_playing.xml` & `NowPlayingActivity.kt`)**:
   - **Dynamic Artwork Ambient Mesh Background**: বর্তমানে যে গান চলছে তার থাম্বনেইল থেকে স্বয়ংক্রিয়ভাবে ডমিন্যান্ট কালার (`Bitmap` sampling) নিয়ে পুরো প্লেয়ারের পেছনে ডার্ক অ্যাম্বিয়েন্ট মেশ গ্র্যাডিয়েন্ট তৈরি হয়।
   - **Top Grab Handle & Swipe-to-Minimize**: উপরে Apple Music-এর মতো পিল হ্যান্ডেল এবং যেকোনো জায়গায় নিচে সোয়াইপ করলে মিনি-প্লেয়ারে মিনিমাইজ হয়।
   - **Spring Breathing Album Artwork**: গান চলাকালীন অ্যালবাম আর্ট `1.0x` সাইজ ও `28dp` ডিপ শ্যাডোতে থাকে; পজ করলে Apple Music-এর মতোই স্প্রিং অ্যানিমেশনে (`OvershootInterpolator`) ছোট হয়ে `0.80x` সাইজে চলে আসে।
   - **Title, Marquee, Favorite (`★/♥`) & More (`•••`) Pill Buttons**: গানের নামের পাশে ট্রান্সলুসেন্ট গ্লাস সার্কেল বাটন; `•••` বাটনে ট্যাপ করলে `Start Personal Station`, `Playback Speed`, `Download Lossless Audio`, এবং `Share Song Link` মেনু ওপেন হয়।
   - **Apple Music Progress Scrubber & Lossless Badge**: স্লিক প্রোগ্রেস বার (ড্র্যাগ করলে বড় হয়), বাম পাশে অতিবাহিত সময় (`0:48`), মাঝখানে ট্যাপযোগ্য **`LOSSLESS`** ব্যাজ এবং ডান পাশে বাকি থাকা সময় নেগেটিভ ফরম্যাটে (`-2:32`) দেখায়।
   - **Giant Crisp White Transport Controls**: বর্ডারলেস বড় সাদা `Previous`, `Play/Pause`, এবং `Next` বাটন, যেখানে ট্যাপ করলে হ্যাপটিক ফিডব্যাক ও বাউন্স অ্যানিমেশন (`0.86x -> 1.0x`) কাজ করে।
   - **Hardware Volume Slider Bar**: প্লে কন্ট্রোলগুলোর ঠিক নিচে Apple Music-এর মতো স্পিকার আইকনসহ রিয়েল-টাইম ভলিউম স্লাইডার (`AudioManager.STREAM_MUSIC` এর সাথে সরাসরি যুক্ত)।
   - **Bottom 4-Icon Action Dock**:
     1. **Lyrics / Info (`lyricsButton`)**: ট্যাপ করলে অ্যালবাম আর্টের জায়গায় গানের লিরিক্স/ডেসক্রিপশন ওভারলে ওপেন হয়।
     2. **Station (`radioButton`)**: বর্তমান গান থেকে অটো-রেডিও কিউ শুরু করে।
     3. **Download (`downloadButton`)**: অডিও ডাউনলোড শিট ওপেন করে এবং ডাউনলোড প্রোগ্রেস দেখায়।
     4. **Up Next Queue (`queueButton` & `upNextCard`)**: পরবর্তী গানের প্রিভিউ পিল এবং ফুল কিউ শিট (`QueueSheet`) ওপেন করে।

---

## ২. জিপের ভেতরে কোন ফাইল কোথায় আছে (Included Files)

```text
SparkTube-v1.5.0-AppleMusic-Upgrade.zip
├── README_UPGRADE.md                  # এই গাইড ফাইলটি
├── push_and_release.sh                # ১-কমান্ডে GitHub push ও Release v1.5.0 তৈরির অটোমেটিক স্ক্রিপ্ট
└── app/
    ├── build.gradle.kts               # versionCode = 11, versionName = "1.5.0"
    └── src/main/
        ├── java/com/sparktube/app/ui/
        │   ├── MainActivity.kt        # অটো-আপডেট চেক রিমুভড + মিনি প্লেয়ার আপডেট
        │   ├── menu/
        │   │   └── MenuFragment.kt    # Backup, Update, Telegram, About রিমুভড
        │   └── music/
        │       ├── MusicFragment.kt   # Apple Music Home / Listen Now লজিক ও ইন্টারেকশন
        │       └── NowPlayingActivity.kt # Apple Music ফুল-স্ক্রিন প্লেয়ার ও ডাইনামিক মেশ লজিক
        └── res/
            ├── layout/
            │   ├── fragment_menu.xml         # ক্লিন সেটিংস মেনু লেআউট
            │   ├── fragment_music.xml        # Apple Music Home লেআউট
            │   ├── activity_now_playing.xml  # Apple Music Now Playing লেআউট
            │   ├── item_music_card.xml       # 156dp রাউন্ডেড অ্যালবাম কার্ড
            │   └── item_music_row.xml        # Apple Music হেয়ারলাইন ডিভাইডার সং রো
            └── values/
                └── colors.xml                # Apple Music Crimson (#FA243C) প্যালেট
```

---

## ৩. কীভাবে `main` ব্রাঞ্চে Push ও `v1.5.0` Release করবেন (How to Run)

### পদ্ধতি ১: অটোমেটিক ১-কমান্ড স্ক্রিপ্ট (Linux / macOS / Termux / Git Bash)
আপনার পিসি বা ফোনের Termux-এ জিপ ফাইলটি ডাউনলোড করে নিচের কমান্ডটি রান করুন:

```bash
unzip -o SparkTube-v1.5.0-AppleMusic-Upgrade.zip -d sparktube_patch
cd sparktube_patch
GITHUB_PAT="আপনার_নতুন_GITHUB_PAT_টোকেন" bash ./push_and_release.sh
```

**এই স্ক্রিপ্টটি যা করবে:**
1. `https://github.com/devfahim00/SparkTube.git` এর `main` ব্রাঞ্চ ক্লোন করবে।
2. জিপের ভেতরের সব আপগ্রেডেড `app/` ফাইলগুলো রিপোজিটরিতে কপি করবে।
3. `feat(ui): Apple Music pixel-accurate UI/UX & remove Backup/Update/Telegram/About from Menu (v1.5.0)` মেসেজ দিয়ে `main` ব্রাঞ্চে কমিট ও পুশ করবে।
4. `v1.5.0` ট্যাগ তৈরি করে GitHub-এ পুশ করবে।
5. আপনার রিপোজিটরির [`.github/workflows/build.yml`](https://github.com/devfahim00/SparkTube/blob/main/.github/workflows/build.yml) স্বয়ংক্রিয়ভাবে `v1.5.0` ট্যাগ ডিটেক্ট করে `SparkTube-v1.5.0.apk` বিল্ড করবে এবং GitHub Releases পেজে নতুন রিলিজ পাবলিশ করে দেবে!

---

### পদ্ধতি ২: আপনার লোকালে ক্লোন করা প্রজেক্টে ম্যানুয়ালি কপি করে Push করা
যদি আপনার পিসিতে বা Android Studio-তে আগে থেকেই `SparkTube` প্রজেক্ট ক্লোন করা থাকে:

1. জিপ ফাইলটি আনজিপ করুন।
2. জিপের ভেতরের `app/` ফোল্ডারটি আপনার প্রজেক্টের রুট ফোল্ডারে পেস্ট করে ফাইলগুলো Replace করুন।
3. টার্মিনালে নিচের কমান্ডগুলো রান করুন:

```bash
git add -A
git commit -m "feat(ui): Apple Music UI/UX upgrade & clean Settings menu (v1.5.0)"
git push origin main
git tag v1.5.0
git push origin v1.5.0
```

---

## ৪. জরুরি সিকিউরিটি নোট (Security Notice)
আপনি চ্যাটে যে GitHub Personal Access Token (`github_pat_...`) শেয়ার করেছিলেন, নিরাপত্তার স্বার্থে কাজ শেষ হওয়ার পর **GitHub Settings → Developer settings → Personal access tokens → Fine-grained tokens** থেকে পুরনো টোকেনটি Delete/Revoke করে নেবেন।
