# ⚡ VidSnap Pro - Universal Social Video & Music Downloader

**VidSnap** is a high-performance Android application built with **Jetpack Compose** for downloading videos and audio from **Instagram, YouTube, TikTok (without watermark), Facebook, Twitter/X, Pinterest**, and any website. Featuring an AMOLED dark UI inspired by modern video downloader designs, with 3D floating social tiles, high-speed streaming engine, and instant 1-click cloud compilation via GitHub Actions.

---

## ✨ Features & Upgrades

- 🚀 **New Reliable Active Streaming Engine**:
  - Replaced system `DownloadManager` limitations with an active **OkHttp Stream Downloader**.
  - Direct byte-streaming with animated real-time progress bars (0% ➔ 100%).
  - Immune to Android 10-15 Scoped Storage crashes and 403 Forbidden CDN header blocks.
  - Automatically exports files to `/storage/emulated/0/Download/VidSnap/` and scans into Android's Gallery.
- 🎨 **Modern 2026 Brand & Logo**:
  - Sleek aerodynamic "V" chevron vector icon with embedded high-speed download arrow.
  - Brand name: **VidSnap**.
  - Golden-Amber gradient accents (`#FFC400` to `#FF9500`).
- 🎬 **Multi-Platform Support**:
  - **YouTube**: Direct unthrottled streaming in 1080p FHD, 720p HD, 480p, and MP3 audio.
  - **TikTok**: High-definition video with watermark removed via TikWM engine.
  - **Instagram & Facebook**: Integrated in-app browser media sniffer that captures real CDN streams as you browse and watch reels.
- ⏯️ **Built-in Media Library & Player**:
  - Watch videos or listen to downloaded audio right inside the app using **ExoPlayer (Media3)**.
- ☁️ **1-Click GitHub Actions Cloud Compilation**:
  - Compile the APK directly in the cloud without needing Android Studio or Java installed locally.

---

## 🚀 How to Compile via GitHub Cloud Service Actions Button

### Step 1: Push to your GitHub Repository

```bash
cd C:\Users\abuhu\.gemini\antigravity\scratch\snaptube-downloader
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/vidsnap-downloader.git
git branch -M main
git push -u origin main
```

### Step 2: Click the "Run workflow" Cloud Service Button

1. Go to your repository on GitHub.
2. Click the **"Actions"** tab at the top.
3. Select **"Build VidSnap Downloader APK"** on the left.
4. Click the **"Run workflow"** button on the right and confirm.

### Step 3: Download the Compiled APK

1. The cloud builder finishes in **~2 minutes**.
2. Click on the completed workflow run with the green checkmark (`✔`).
3. Scroll down to **"Artifacts"** and click **`VidSnap-Downloader-APK`**.
4. Install `VidSnap-Downloader.apk` on your phone!
