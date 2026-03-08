# ⌁ FocusGate — The Awareness Gate

> *A digital wellbeing tool designed to cure mindless scrolling through intentional phone usage, AI-driven habit correction, and structured focus sessions.*

---

## Architecture Overview

```
FocusGate/
├── app/src/main/
│   ├── AndroidManifest.xml          ← All permissions + service declarations
│   ├── kotlin/com/focusgate/app/
│   │   ├── FocusGateApp.kt          ← Application class, notification channels
│   │   ├── MainActivity.kt          ← Dashboard + permission setup + onboarding
│   │   │
│   │   ├── state/
│   │   │   └── FocusStateManager.kt ← SSOT: DataStore-backed persistent state
│   │   │
│   │   ├── services/
│   │   │   ├── FocusAccessibilityService.kt   ← Core engine: app monitoring + nudges
│   │   │   ├── NotificationInterceptorService.kt ← Notification capture + digest gen
│   │   │   └── FocusMonitorService.kt          ← Persistent keepalive service
│   │   │
│   │   ├── ai/
│   │   │   └── GeminiService.kt     ← Gemini 1.5 Flash integration
│   │   │
│   │   ├── data/
│   │   │   └── NotificationQueue.kt ← Thread-safe in-memory notification queue
│   │   │
│   │   ├── receiver/
│   │   │   └── ScreenStateReceiver.kt ← Screen unlock/off/boot handler
│   │   │
│   │   └── ui/
│   │       ├── theme/Theme.kt       ← Zen color palette + typography
│   │       ├── intentgate/
│   │       │   └── IntentGateActivity.kt  ← "The Oasis" full-screen overlay
│   │       ├── workmode/
│   │       │   └── WorkModeActivity.kt    ← Focus Launcher + setup screen
│   │       ├── digest/
│   │       │   └── PostFocusDigestActivity.kt ← AI-powered notification summary
│   │       └── nudge/
│   │           └── NudgeOverlayManager.kt ← WindowManager overlay system
│   │
│   └── res/xml/
│       ├── accessibility_service_config.xml
│       └── notification_listener_config.xml
```

---

## Feature Implementation Details

### 1. Intent Gate (The Oasis)

**Trigger**: `ScreenStateReceiver` listens for `ACTION_USER_PRESENT` (fires after PIN/biometric unlock).

**Flow**:
```
Screen Unlocked
    └→ ScreenStateReceiver.handleUnlock()
        └→ Check: WorkMode active? → skip
        └→ Check: Gate enabled?    → skip
        └→ Launch IntentGateActivity (FLAG_ACTIVITY_NEW_TASK | SHOW_WHEN_LOCKED)
            └→ User types intent
            └→ FocusStateManager.setCurrentIntent()
            └→ GeminiService.parseIntentCategory() [background]
            └→ Gate dismisses, user proceeds
```

**UI**: Full-screen Compose overlay with:
- Breathing orb ambient animation (4s sine wave scale)
- Serif headline "Why are you opening your phone?"
- Multi-line OutlinedTextField
- Quick-intent suggestion chips
- Entrance animation (fade + slide)
- Back button vibration feedback (cannot dismiss without intent)

### 2. Work Mode (Focus Launcher)

**Activation**: User configures duration + app whitelist → `FocusStateManager.startWorkMode()`.

**Enforcement**: `WorkModeActivity` is registered as a HOME launcher in the manifest. When the AccessibilityService detects a non-whitelisted app, it calls `performGlobalAction(GLOBAL_ACTION_HOME)` which routes to WorkModeActivity (not the system launcher).

**Emergency Exit**: Randomly selected 50-word paragraph must be typed exactly to exit early. This friction is intentional.

**Timer**: Real-time countdown via `LaunchedEffect` coroutine polling `remainingMillis`.

### 3. Nudge System

**Level 1 – Warning** (Visual Reminder):
- Slides in from top via `WindowManager TYPE_ACCESSIBILITY_OVERLAY`
- Shows: original intent vs current app
- Auto-dismisses in 5 seconds

**Level 2 – Grayscale** (Screen desaturation):
- `ColorMatrix(saturation: 0f)` applied to a hardware-accelerated `FrameLayout`
- Drawn over entire screen via `TYPE_ACCESSIBILITY_OVERLAY`
- Fade in 600ms / fade out 400ms

**Level 3 – Block** (App removal):
- `performGlobalAction(GLOBAL_ACTION_HOME)` boots user out
- Bottom sheet banner shown explaining the block

**Escalation logic**: Stored in `FocusStateManager.NUDGE_LEVEL`. Each `escalateNudge()` call increments by 1. Reset on:
- New intent declared
- Screen off
- User manually returns to stated intent app

### 4. Notification Interceptor

**Capture**: `NotificationInterceptorService.onNotificationPosted()` checks `FocusStateManager.isWorkModeActiveNow()`. If active, calls `cancelNotification(sbn.key)` to suppress it and adds to `NotificationQueue`.

**Exceptions**: `Notification.CATEGORY_CALL` and `CATEGORY_ALARM` always pass through.

**Digest Generation** (on session end):
```
NotificationQueue.drainAll()
    └→ GeminiService.summarizeNotifications(list)
        └→ Prompt: "Calm, categorized summary of ${n} notifications"
        └→ Returns plain text digest
    └→ PostFocusDigestActivity renders:
        - Total count stat card
        - AI summary card
        - Per-app breakdown with counts
```

### 5. AI Integration (Gemini 1.5 Flash)

**Model**: `gemini-1.5-flash` – fast, cost-effective for frequent intent checks.

**Two prompt types**:

1. **Intent Match** (fires every ~3s debounced):
   ```
   Intent: "Study coding" + App: "TikTok" → {"match": false}
   Intent: "Study coding" + App: "Chrome" → {"match": true}
   ```
   Fail-safe: returns `true` on network error (no false nudges).

2. **Notification Summary** (fires once at session end):
   Structured prompt requesting calm, categorized digest in plain text.

**Temperature**: 0.1 (low randomness for consistent intent classification).

---

## Required Permissions & Why

| Permission | Why |
|-----------|-----|
| `SYSTEM_ALERT_WINDOW` | Draw Intent Gate overlay above all apps |
| `BIND_ACCESSIBILITY_SERVICE` | Monitor foreground app + apply nudges |
| `PACKAGE_USAGE_STATS` | Read which app is in foreground |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Intercept/cancel notifications |
| `FOREGROUND_SERVICE` | Keep monitor service alive |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after device reboot |
| `WAKE_LOCK` | Ensure gate appears even on locked screen |
| `QUERY_ALL_PACKAGES` | Build app whitelist for Work Mode |

---

## Setup Instructions

### 1. Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 35
- Kotlin 2.0+
- A Google Gemini API key (free at [ai.google.dev](https://ai.google.dev))

### 2. Build Setup
```bash
git clone <repo>
cd FocusGate

# Add your Gemini API key to local.properties:
echo 'GEMINI_API_KEY=AIzaSy...' >> local.properties

# Build debug APK
./gradlew assembleDebug

# Install to device
./gradlew installDebug
```

### 3. First Run Permissions

After installing, the app will guide you through granting permissions:

1. **Accessibility Service**: Settings → Accessibility → FocusGate → Enable
2. **Notification Listener**: Settings → Special app access → Notification access → FocusGate
3. **Draw Over Other Apps**: Prompted automatically, tap "Allow"
4. **Usage Access**: Settings → Digital Wellbeing → Usage Access → FocusGate

### 4. Add Gemini API Key
Go to the **Setup** tab in the app → paste your Gemini API key → Save.

---

## Design System

### Color Palette
```
Sage Deep    #4A6741  ← Primary actions, active states
Sage Mid     #6B8F5E  ← Secondary elements
Sage Pale    #D4E8CC  ← Card backgrounds, chips
Ivory        #FAF8F4  ← Main background
Cream        #F3EFE7  ← Card surfaces
Linen        #E8E0D3  ← Dividers, borders
Earth Deep   #5C4033  ← Primary text
Earth Mid    #7D5A4A  ← Secondary text
Earth Light  #B08070  ← Placeholder, hints
Clay Warm    #B87355  ← CTA accent
Stone Deep   #3D3530  ← Dark mode background
```

### Typography
- **Display/Headlines**: `FontFamily.Serif` (production: Canela or Freight Display)
- **Body/Labels**: `FontFamily.SansSerif` (production: GT America or Neue Haas Grotesk)

---

## Known Limitations & Notes

1. **Grayscale**: The `TYPE_ACCESSIBILITY_OVERLAY` + `ColorMatrix` approach works on most devices but may be overridden by some OEM launchers. Samsung and Xiaomi devices may require additional configuration.

2. **OEM Compatibility**: Huawei/EMUI, MIUI, and ColorOS have aggressive battery optimization that may kill background services. Users should add FocusGate to the battery optimization whitelist.

3. **Gemini API Cost**: Intent checks are debounced at 3 seconds to minimize API calls. A typical session (~50 app switches) costs < $0.001 with Gemini Flash.

4. **Work Mode HOME**: For Work Mode to fully replace the home screen, users must set FocusGate as their default launcher during active sessions OR use the AccessibilityService enforcement path.

5. **Privacy**: Notification content is held in memory only during the session. No notification data is persisted to disk or sent to any server except the Gemini API for summarization.

---

## License
MIT License. Build mindfully. 🌿
