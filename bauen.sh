#!/bin/bash
# Baut aus den lose hochgeladenen Dateien die Projektstruktur für Android,
# erzeugt App-Logo und Einstellungen, baut die APK und prüft sie.
set -e

mkdir -p app/src/main/java/de/pdfapp
mkdir -p app/src/main/res/drawable
mkdir -p app/src/main/res/mipmap-anydpi-v26
mkdir -p app/src/main/res/values
mv MainActivity.kt app/src/main/java/de/pdfapp/MainActivity.kt
mv AndroidManifest.xml app/src/main/AndroidManifest.xml
mv debug.keystore app/debug.keystore

cat > settings.gradle.kts <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "valpdf"
include(":app")
EOF

cat > build.gradle.kts <<'EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
}
EOF

cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
EOF

cat > app/build.gradle.kts <<'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.pdfapp"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "de.valpdf.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
EOF

# ---- Logo (Stockente, gezeichnet als Vektorgrafik) ----
cat > app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
EOF
cp app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml

cat > app/src/main/res/drawable/ic_launcher_background.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- VALPDF Logo: Hintergrund -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M 0 0 L 108 0 L 108 108 L 0 108 Z"
        android:fillColor="#CDE9F5" />
    <path
        android:pathData="M 0 70 L 108 70 L 108 108 L 0 108 Z"
        android:fillColor="#B5DCEE" />
    <path
        android:pathData="M 24 29 C 24 22.3724, 29.3724 17, 36 17 C 42.6276 17, 48 22.3724, 48 29 C 48 35.6276, 42.6276 41, 36 41 C 29.3724 41, 24 35.6276, 24 29 Z"
        android:fillColor="#FFF6CC"
        android:fillAlpha="0.6" />
    <path
        android:pathData="M 27.5 29 C 27.5 24.30545, 31.30545 20.5, 36 20.5 C 40.69455 20.5, 44.5 24.30545, 44.5 29 C 44.5 33.69455, 40.69455 37.5, 36 37.5 C 31.30545 37.5, 27.5 33.69455, 27.5 29 Z"
        android:fillColor="#FFEFA8" />
    <path
        android:pathData="M 48 20 C 48 16 54 15 56 18 C 58 14 66 15 66 20 C 70 20 70 25 66 25 L 50 25 C 46 25 45 21 48 20 Z"
        android:fillColor="#FFFFFF"
        android:fillAlpha="0.85" />
</vector>
EOF

cat > app/src/main/res/drawable/ic_launcher_foreground.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- VALPDF Logo: Stockente, Vordergrund -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M 22 68 C 34 71, 62 71, 80 68 C 76 72, 30 73, 22 68 Z"
        android:fillColor="#2F7DAE"
        android:fillAlpha="0.35" />
    <path
        android:pathData="M 26 50 C 21 47, 20 42, 23 38 C 26 40, 29 44, 32 49 Z"
        android:fillColor="#2B3342"
        android:strokeColor="#3A2A22"
        android:strokeWidth="1.2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 23.2 38.6 C 21.4 36.8, 22.4 34.6, 24.6 35"
        android:strokeColor="#2B3342"
        android:strokeWidth="1.3"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 25 50 C 31 51, 41 51, 53 50.5 C 59 50, 64 47.5, 68 45.5 C 76 47, 81 54, 78 62 C 76 66, 72 69, 68 70 C 56 73, 38 72, 29 63 C 26 59, 24 54, 25 50 Z"
        android:fillColor="#CBC5B8"
        android:strokeColor="#3A2A22"
        android:strokeWidth="1.4"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 30 63 C 40 70, 58 71, 68 70 C 56 74, 38 73, 30 63 Z"
        android:fillColor="#F2EEE4"
        android:fillAlpha="0.9" />
    <path
        android:pathData="M 63 47.5 C 70 46, 78 48.5, 79.5 55.5 C 80.5 62, 76.5 67.5, 71 69.5 C 67 70, 63.5 69, 61.5 67 C 65.5 61, 65.5 54, 63 47.5 Z"
        android:fillColor="#8C4A2A"
        android:strokeColor="#3A2A22"
        android:strokeWidth="1.3"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 70 53 L 71.5 54.5 M 74 57 L 75.5 58.5 M 69 59 L 70.5 60.5 M 73 62 L 74.5 63.5"
        android:strokeColor="#5E2E19"
        android:strokeWidth="0.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeAlpha="0.8" />
    <path
        android:pathData="M 35 53 C 45 48, 58 50, 65 56 C 57 63, 44 63, 33 59 C 32 57, 33 55, 35 53 Z"
        android:fillColor="#AFA594"
        android:strokeColor="#3A2A22"
        android:strokeWidth="1.3"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 38 57 L 43 54 M 43 59 L 48 55.5 M 36.5 60 L 41 58 M 49 60 L 54 57"
        android:strokeColor="#7F7666"
        android:strokeWidth="0.9"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeAlpha="0.9" />
    <path
        android:pathData="M 49 56 C 54 54, 60 55, 63 57 C 59 60.5, 53.5 60.5, 48.5 59 Z"
        android:fillColor="#2E5FB3"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="0.9"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 64.5 47.5 C 63.5 43.5, 65.5 40.5, 66 36 C 66.5 29.5, 71 26, 76.5 26 C 82 26, 85.5 30.5, 84.5 36 C 84 40, 82.5 42.5, 80.5 44.5 C 79.5 46, 80 47.5, 81 48.5 C 76 50, 69.5 50, 64.5 47.5 Z"
        android:fillColor="#1E8A55"
        android:strokeColor="#3A2A22"
        android:strokeWidth="1.4"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 68.5 34 C 69.5 30, 72.5 28.8, 76 28.8"
        android:strokeColor="#7FD9A6"
        android:strokeWidth="1.3"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeAlpha="0.9" />
    <path
        android:pathData="M 67.8 41 C 67.5 39.5, 67.6 38.5, 67.9 37.5"
        android:strokeColor="#7FD9A6"
        android:strokeWidth="1.0"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeAlpha="0.8" />
    <path
        android:pathData="M 64.2 47 C 70 50, 77 50, 81.2 48.6 L 82 52.2 C 76 54.4, 69 54, 63.6 50.8 Z"
        android:fillColor="#FFFFFF"
        android:strokeColor="#3A2A22"
        android:strokeWidth="1.0"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 83.6 32.8 C 87.5 31.6, 92.5 32.4, 95 35.4 C 94.6 38.8, 89.5 40.6, 83.8 39.6 C 85 37.6, 85 34.8, 83.6 32.8 Z"
        android:fillColor="#F3CC3A"
        android:strokeColor="#3A2A22"
        android:strokeWidth="1.2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 85 37.6 C 88.5 38.4, 91.8 37.8, 94.2 36.4"
        android:strokeColor="#3A2A22"
        android:strokeWidth="0.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 92.2 33.8 C 94.2 34.6, 95 36, 94.4 37"
        android:fillColor="#C9A21E"
        android:strokeColor="#3A2A22"
        android:strokeWidth="0.6"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 88.05 34.8 C 88.05 34.496235, 88.296235 34.25, 88.6 34.25 C 88.90376499999999 34.25, 89.14999999999999 34.496235, 89.14999999999999 34.8 C 89.14999999999999 35.103764999999996, 88.90376499999999 35.349999999999994, 88.6 35.349999999999994 C 88.296235 35.349999999999994, 88.05 35.103764999999996, 88.05 34.8 Z"
        android:fillColor="#3A2A22" />
    <path
        android:pathData="M 76.6 32.4 C 76.6 31.295399999999997, 77.49539999999999 30.4, 78.6 30.4 C 79.7046 30.4, 80.6 31.295399999999997, 80.6 32.4 C 80.6 33.504599999999996, 79.7046 34.4, 78.6 34.4 C 77.49539999999999 34.4, 76.6 33.504599999999996, 76.6 32.4 Z"
        android:fillColor="#FFF3C8"
        android:strokeColor="#3A2A22"
        android:strokeWidth="0.6"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="M 77.65 32.5 C 77.65 31.809625, 78.209625 31.25, 78.9 31.25 C 79.59037500000001 31.25, 80.15 31.809625, 80.15 32.5 C 80.15 33.190375, 79.59037500000001 33.75, 78.9 33.75 C 78.209625 33.75, 77.65 33.190375, 77.65 32.5 Z"
        android:fillColor="#1A1410" />
    <path
        android:pathData="M 78.89999999999999 32.0 C 78.89999999999999 31.77908, 79.07907999999999 31.6, 79.3 31.6 C 79.52092 31.6, 79.7 31.77908, 79.7 32.0 C 79.7 32.22092, 79.52092 32.4, 79.3 32.4 C 79.07907999999999 32.4, 78.89999999999999 32.22092, 78.89999999999999 32.0 Z"
        android:fillColor="#FFFFFF" />
    <path
        android:pathData="M -6 68 C 0 65, 5 70.5, 10 68 C 16 65, 21 70.5, 28 68 C 34 65, 40 70.5, 46 68 C 52 65, 58 70.5, 64 68 C 70 65, 76 70.5, 82 68 C 88 65, 94 70.5, 100 68 C 105 65.5, 110 69, 116 67 L 116 120 L -6 120 Z"
        android:fillColor="#4A9ACB"
        android:fillAlpha="0.93" />
    <path
        android:pathData="M -6 68 C 0 65, 5 70.5, 10 68 C 16 65, 21 70.5, 28 68 C 34 65, 40 70.5, 46 68 C 52 65, 58 70.5, 64 68 C 70 65, 76 70.5, 82 68 C 88 65, 94 70.5, 100 68 C 105 65.5, 110 69, 116 67"
        android:strokeColor="#3A2A22"
        android:strokeWidth="1.0"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeAlpha="0.5" />
    <path
        android:pathData="M 18 75 C 22 73, 26 77, 30 75 M 44 77 C 48 75, 52 79, 56 77 M 70 75 C 74 73, 78 77, 82 75"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.1"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeAlpha="0.65" />
    <path
        android:pathData="M 24 82 C 28 80, 32 84, 36 82 M 58 83 C 62 81, 66 85, 70 83"
        android:strokeColor="#2F7DAE"
        android:strokeWidth="1.0"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeAlpha="0.6" />
</vector>
EOF

cat > app/src/main/res/values/strings.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">VALPDF</string>
</resources>
EOF

# ---- APK bauen ----
gradle assembleDebug --no-daemon --stacktrace

# ---- APK prüfen (Ergebnis steht im Protokoll) ----
APK=app/build/outputs/apk/debug/app-debug.apk
echo "=== APK-Größe ==="
ls -l "$APK"
SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
if [ -n "$BT" ] && [ -x "$BT/apksigner" ]; then
  echo "=== Signatur-Prüfung ==="
  "$BT/apksigner" verify --verbose --print-certs "$APK"
fi
if [ -n "$BT" ] && [ -x "$BT/aapt2" ]; then
  echo "=== APK-Infos ==="
  "$BT/aapt2" dump badging "$APK" | head -8
fi
