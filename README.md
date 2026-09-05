# 📱 Snaptube - Social Video & Music Downloader

A modern Android application built with **Jetpack Compose** for downloading videos and audio from **Instagram, YouTube, TikTok (without watermark), Facebook, Twitter/X, Pinterest**, and more. The user interface faithfully mirrors the Snaptube design with a dark AMOLED aesthetic, 3D social icons header, yellow branding, and streamlined search-to-download workflow.

---

## ✨ Features

- 🎨 **Snaptube Faithful UI**:
  - Tilted 3D floating social media tiles header with smooth gradient fade into deep black.
  - Top navigation tabs: `Search`, `YouTube`, `Music`, `More` with active yellow indicators.
  - Bold golden-yellow **"Snaptube"** branding.
  - Pill search bar: Download tray icon + "Search to download" hint + circular yellow search button.
  - Bottom navigation bar: `Download`, `Play` (built-in video player), `Settings`.
- ⚡ **Multi-Platform Video Downloader**:
  - **Instagram**: Reels, posts, stories, IGTV.
  - **YouTube**: Videos, Shorts, audio streams in multiple resolutions.
  - **TikTok**: HD videos without watermark.
  - **Facebook & Twitter/X**: Direct video downloads.
- 🎬 **Quality Selector Modal**:
  - **Video**: 1080p FHD, 720p HD, 480p, 360p (MP4).
  - **Audio**: 320kbps MP3, 128kbps M4A.
- 🌐 **In-App Browser with Floating Snaptube Button**:
  - Browse YouTube and other sites directly inside the app with an omnipresent yellow download button.
- ⏯️ **Built-in Media Player ("Play" Tab)**:
  - Play downloaded videos and music instantly with ExoPlayer (Media3).
- ☁️ **1-Click GitHub Cloud Actions Compilation**:
  - Automatically compiles into an installable `.apk` directly in the cloud using GitHub Actions.

---

## 🚀 How to Compile via GitHub Cloud Service Actions Button

You **do not** need Android Studio or Java installed on your computer. You can compile the ready-to-install Android APK directly in GitHub's cloud.

### Step 1: Push this project to your GitHub account

Open your terminal in this directory (`C:\Users\abuhu\.gemini\antigravity\scratch\snaptube-downloader`) and run:

```bash
# 1. Create a new repository on GitHub (e.g. named 'snaptube-downloader')
# 2. Link your local repo to GitHub:
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/snaptube-downloader.git
git branch -M main
git push -u origin main
```

### Step 2: Click the "Run workflow" Cloud Service Button

1. Go to your repository on GitHub in your web browser.
2. Click on the **"Actions"** tab at the top.
3. In the left sidebar, click on **"Build Snaptube Downloader APK"**.
4. On the right side, click the **"Run workflow"** dropdown button.
5. Select the `main` branch and click the green **"Run workflow"** button.

### Step 3: Download the Compiled APK

1. The cloud runner will compile the Android APK in **2 to 3 minutes**.
2. When the build completes with a green checkmark (`✔`), click on the run.
3. Scroll down to the **"Artifacts"** section.
4. Click on **`Snaptube-Downloader-APK`** to download your zip containing `Snaptube-Downloader.apk`.
5. Transfer the `.apk` to your Android phone (or download it directly from GitHub on your phone) and install it!

---

## 🛠️ Project Architecture

```
snaptube-downloader/
├── .github/workflows/
│   └── build-apk.yml          # GitHub Actions 1-click cloud build workflow
├── app/
│   ├── src/main/
│   │   ├── java/com/snaptube/downloader/
│   │   │   ├── MainActivity.kt               # Root Activity & Bottom Navigation
│   │   │   ├── core/
│   │   │   │   ├── extractor/
│   │   │   │   │   └── VideoExtractorEngine.kt # Universal stream resolver
│   │   │   │   └── download/
│   │   │   │       └── DownloadHelper.kt     # Background download manager
│   │   │   ├── data/model/
│   │   │   │   ├── MediaFormat.kt            # Quality & resolution types
│   │   │   │   └── DownloadItem.kt           # Media task state
│   │   │   └── ui/
│   │   │       ├── components/
│   │   │       │   ├── SocialGridHeader.kt   # 3D tilted icons with gradient fade
│   │   │       │   ├── SearchDownloadBar.kt  # Stadium pill search & yellow button
│   │   │       │   ├── TopTabBar.kt          # Top tabs with yellow indicator
│   │   │       │   └── DownloadBottomSheet.kt# Quality selection bottom sheet
│   │   │       ├── screens/
│   │   │       │   ├── HomeScreen.kt         # Exact replica of screenshot
│   │   │       │   ├── BrowserScreen.kt      # In-app browser with floating button
│   │   │       │   ├── PlayScreen.kt         # Video & audio ExoPlayer library
│   │   │       │   └── SettingsScreen.kt     # Preferences & quality options
│   │   │       └── theme/                    # Snaptube AMOLED color palette
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
└── settings.gradle.kts
```
