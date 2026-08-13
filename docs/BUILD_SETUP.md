# PULSE — Build Setup

**Verified working:** 2026-08-11 on Windows 11 Home (AMD64)

## Installed toolchain

| Component | Version | Location |
|---|---|---|
| Android Studio | 2026.1.3.7 | `C:\Program Files\Android\Android Studio` |
| JDK (Temurin) | 17.0.20+8 | `.tooling/jdk-17.0.20+8` (project-local) |
| Gradle | 9.7.0 | `.tooling/gradle-9.7.0` + wrapper in repo |
| Android SDK | — | `%LOCALAPPDATA%\Android\Sdk` |
| SDK Platform | android-36 | |
| Build-Tools | 36.0.0 | |
| Platform-Tools (adb) | 37.0.1 | |
| Command-line tools | 15859902 | |

Android Studio was installed via `winget install Google.AndroidStudio`. The SDK
was installed **headlessly** via the command-line tools rather than Studio's
first-run GUI wizard, so the whole toolchain is reproducible from a script.

## Building

`JAVA_HOME` must point at the project-local JDK (Android Studio's bundled JBR
also works). From the project root:

```bash
export JAVA_HOME="$PROJECT_ROOT/.tooling/jdk-17.0.20+8"
./gradlew.bat :core:model:test        # 28 unit tests
./gradlew.bat :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
```

Current verified output: **28 tests passing, APK 11.8 MB**, `com.pulse.app`,
minSdk 26 / targetSdk 36.

## Version choices and the traps behind them

| Choice | Why |
|---|---|
| **AGP 9.3.1** | AGP 9+ has **built-in Kotlin support** — applying `org.jetbrains.kotlin.android` alongside it is a hard error. That plugin is deliberately absent from `app/build.gradle.kts`. |
| **compileSdk / targetSdk 36** | API 37 is *listed* by the SDK manager but **not actually published** — installing it yields an empty stub directory with no `android.jar`. |
| **core-ktx 1.18.0, lifecycle 2.10.0** | The latest (1.19.0 / 2.11.0) require compileSdk 37, which doesn't exist yet. Pinned one minor back deliberately — revisit when API 37 ships. |
| **minSdk 26** | `java.time` without desugaring. ML Kit only needs 23, so this costs nothing. |
| **JDK 17** | AGP 9 and Gradle 9.7 both run on it; matches `sourceCompatibility`. |
| **Kotlin 2.3.21 + KSP 2.3.11** | KSP changed versioning scheme at 2.3.0 — from `<kotlin>-<ksp>` to plain semver. KSP 2.3.11 is built against Kotlin **2.3.20**, so Kotlin 2.4.x is *ahead* of what KSP supports. Room and Hilt both need KSP, so **Kotlin follows KSP**, not the reverse. |
| **Robolectric for DAO tests** | No emulator or device is attached, and Room DAO behaviour is too important to defer to Phase 12. Robolectric runs the real Room database on the JVM. |
| **`sdk.dir=C\:/Users/...`** | Two separate traps at once. Plain backslashes get mangled by Java `.properties` escaping → misleading *"filename, directory name, or volume label syntax is incorrect"*. But an **unescaped drive-letter colon** is a lint error (`PropertyEscape`). The form satisfying both is an escaped colon with forward slashes. |

## Module layout

```
:app            Android application — Compose, nav host, Hilt application
:core:model     Pure Kotlin, NO Android deps — JVM-unit-testable
:core:domain    Pure Kotlin — TDEE, macros, 1RM, volume, pace, PR detection
:core:database  Room — 23 entities, 13 DAOs, 2 FTS4 indexes, schema export
:core:data      Repositories over the DAOs, with Hilt bindings
```

`:core:model` currently holds `Nutrition` / `FoodNutrition` / `Serving`
(scaling), `EnergyCheck` (confidence scoring), `UnitConverter` (metric-primary
storage per PHASE2_ARCHITECTURE.md §1.1), and `BarcodeNormalizer`
(UPC-E → UPC-A → EAN-13). Remaining `:core:*` and `:feature:*` modules land as
Phase 3 continues.

## Prepopulated database

The bundled food database is built by Python (`tools/build_food_db.py`) but must
satisfy Room's runtime schema verification. `tools/stamp_room_identity.py` reads
the KSP-exported schema JSON, **validates the built database's columns and types
against it**, and only then writes `room_master_table`. Validating before
stamping turns a cryptic runtime crash on first open into a clear build-time
message.

```bash
./gradlew.bat :core:database:assembleDebug     # exports schemas/1.json
python tools/build_food_db.py --lean
python tools/stamp_room_identity.py            # validate, then stamp
```

Current identity hash: `a7f2bd6b0b7fe85bef7286e887762c01` (schema v1).
It changes on **any** entity change — re-stamp after every schema edit.

## Not yet wired

CameraX, ML Kit, Retrofit and Vico. Room and Hilt are both in and working.

Note `androidx.hilt:hilt-navigation-compose` is pinned to **1.3.0** — 1.4.0 hits
the same unpublished-API-37 trap as `core-ktx` and `lifecycle`.

## Reproducing on a clean machine

```bash
winget install Google.AndroidStudio --silent --accept-package-agreements --accept-source-agreements

# JDK 17 (project-local, no admin needed)
curl -sL -o jdk.zip "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
unzip -q jdk.zip -d .tooling/

# Android SDK, headless
curl -sL -o cmdline.zip "https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip"
unzip -q cmdline.zip -d "$ANDROID_HOME/cmdline-tools" && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# then create local.properties with sdk.dir (FORWARD slashes)
```

Note `sdkmanager` prints a deprecation warning pointing at a newer `android`
CLI. The new CLI's package naming (`platforms/android-36`) differs from
`sdkmanager`'s (`platforms;android-36`); `sdkmanager` still works and is used
above.
