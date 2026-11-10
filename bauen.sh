#!/bin/bash
# Baut aus den lose hochgeladenen Dateien die Projektstruktur für Android,
# erzeugt App-Logo und Einstellungen, baut die APK und prüft sie.
set -e

mkdir -p app/src/main/java/de/pdfapp
mkdir -p app/src/main/res/drawable-nodpi
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
rootProject.name = "quackreader"
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
        versionCode = 4
        versionName = "4.0"
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

# ---- Logo (QuackReader-Ente) ----
cat > app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
EOF
cp app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml

mv logo.png app/src/main/res/drawable-nodpi/ic_launcher_foreground.png

cat > app/src/main/res/values/colors.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1A1F23</color>
</resources>
EOF

cat > app/src/main/res/values/strings.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">QuackReader</string>
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
