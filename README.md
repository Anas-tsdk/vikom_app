# ViKom patient app

An Android patient/TV app with a simple ViKom home screen, appointments, contact management, and WebRTC audio/video calling. The Home screen uses the same visual palette as `vikom-web-main`; selecting **Call** opens the established quick-dial and in-call experience.

**Platform:** Android TV (min SDK 25, target SDK 36) | **Language:** Kotlin 2.0.21 | **Architecture:** MVVM

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | ViKom ASP.NET Core API + SQLite for care records; Supabase for Auth, Realtime, calling infrastructure, and cloud check-ins |
| Calling | WebRTC peer-to-peer audio/video (Stream WebRTC Android v1.3.8) |
| Signaling | Authenticated Supabase Edge Function + durable PostgreSQL events; Realtime fallback for local development |
| UI | Android Leanback v1.2.0 (TV-optimized), Jetpack Fragments/LiveData/ViewModel |
| Networking | Ktor HTTP client v2.3.12 + OkHttp engine |
| Image Loading | Glide v4.11.0 |
| Security | EncryptedSharedPreferences (AES256-GCM), Supabase RLS, JWT auth |
| Serialization | Kotlin Serialization v1.6.3 |
| Async | Kotlin Coroutines v1.8.1 |

---

## Features

### Home and appointments
- Patient-friendly Home screen with Call, Appointments, Care team, and Settings
- Upcoming appointment summary backed by the ViKom web API
- Appointment update notifications and an appointment list grouped into upcoming/history
- Patients can accept or decline future invitations; the response is written back through the authenticated web API

### Calling
- WebRTC peer-to-peer audio and video calls, with hybrid microphone support (built-in, USB, Bluetooth; falls back to receive-only mode if no mic is available)
- Separate audio-call and video-call actions; either side can request an in-call upgrade from audio to video and the other person must accept
- Full call state machine: Idle → Initiating → Ringing → Connecting → Connected → Ended
- Full-screen incoming call alerts with screen wake-lock and lock-screen display
- HTTPS recovery and FCM wake-up for temporarily offline devices, plus missed-call notifications when the recipient comes back online
- Call history per contact and globally

### Contacts
- Full contact list with alphabetical sorting and search
- Contact details: email, address, notes, nickname, favorites
- Add, edit, and delete contacts

### Quick Dial
- 4 user-assigned priority slots for one-tap calling to favorite contacts

### Presence
- Real-time online / offline / in-call status with 30-second heartbeat
- Calls can be attempted regardless of contact's online status

### Authentication
- Email/password sign-up with email verification
- Secure encrypted session persistence (AES256-GCM)
- Automatic session refresh
- Password reset with OTP verification

### User Profile
- Profile picture/avatar selection and management

### Settings
- Language selection, ringtone picker, vibration toggle, auto-answer

### AI Assistant
- AI chat dialog infrastructure (AIFragment, topic-based conversations)

### TV / Accessibility
- D-pad remote navigation optimized throughout
- WCAG-compliant color contrast
- Leanback launcher integration
- Signaling service auto-restarts after device reboot (BootReceiver)

---

## Setup

### 1. Clone the repo

```bash
git clone <repo-url>
```

### 2. Configure `local.properties`

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/android/sdk
supabase.url=https://your-project.supabase.co
supabase.key=your-anon-key
backend.base.url=http://127.0.0.1:5084/
```

Use only the Supabase **anon/publishable key** in the Android app. Never put a service-role key, JWT signing secret, Firebase service-account JSON, or another server credential in this file.

`127.0.0.1` works only with `adb reverse` over USB. For cable-free testing on a
reachable network, replace it with the laptop's address, for example:

```properties
backend.base.url=http://192.168.1.25:5084/
```

Then rebuild and reinstall the APK. Start the web backend with
`dotnet run --project backend --launch-profile http`; its `http` profile accepts
LAN clients. A laptop address is not a permanent cross-network solution and may
change. For unattended or different-Wi-Fi use, host the API behind HTTPS or use
a stable private VPN address.

### 3. Supabase database

Create the following tables:

| Table | Key columns |
|---|---|
| `profiles` | `id` (UUID), `username`, `email`, `avatar_url`, `contact_id` (INT), `is_online`, `last_seen`, `device_type`, `webrtc_status`, `has_seen_onboarding`, `fcm_token` (nullable TEXT) |
| `contacts` | `id`, `user_id`, `contact_id` (INT), `email`, `address`, `notes`, `nickname`, `is_favorite` |
| `call_history` | `id`, `user_id`, `contact_id` (INT), `contact_name`, `call_type`, `call_method`, `media_type`, `call_duration`, `call_timestamp`, `connection_quality`, `ice_connection_state`, `end_reason`, `notes` |
| `priority_contacts` | `user_id`, `slot` (INT), `contact_id` |
| `call_signaling_events` | Durable private call offers, answers, ICE and control messages; created by the supplied migration |

Enable Row-Level Security (RLS) on all tables.

The signed-in user must be allowed to update only their own `profiles` row so the app can register and clear `fcm_token`. Keep reads/sends that need wider access on a trusted server.

### 4. Production calling, offline recovery, and restricted networks

Realtime-only signaling is not sufficient when a tablet process is stopped or a network blocks WebSockets. ViKom therefore includes a private HTTPS signaling function, durable call events, FCM wake-up/missed-call delivery, and support for short-lived managed TURN credentials.

Apply and deploy these files once to the same Supabase project used by both tablets:

- `supabase/migrations/20260914_durable_call_events.sql`
- `supabase/functions/call-infrastructure/index.ts`
- `supabase/config.toml`

Using the Supabase CLI:

```bash
cd /path/to/vikom_app-main
npx supabase@latest login
npx supabase@latest link --project-ref YOUR_PROJECT_REF
npx supabase@latest db push
npx supabase@latest functions deploy call-infrastructure --no-verify-jwt
```

`verify_jwt=false` only disables the gateway's Supabase-only JWT check. The function itself still authenticates every request as either the signed-in Supabase patient or an authorized web-portal staff JWT. The signaling table is inaccessible to `anon` and `authenticated`; only the function's server-side service role can access it.

Set the following in **Supabase Dashboard → Edge Functions → Secrets**. Do not put them in `local.properties`, `google-services.json`, the Vite environment, source control, or an APK:

| Secret | Value |
|---|---|
| `PORTAL_JWT_SECRET` | Exactly the web backend's `Jwt:Key` |
| `PORTAL_JWT_ISSUER` | Exactly the web backend's `Jwt:Issuer` |
| `PORTAL_JWT_AUDIENCE` | Exactly the web backend's `Jwt:Audience` |
| `PORTAL_ALLOWED_ORIGINS` | Comma-separated exact portal origins, such as `http://localhost:5173` |
| `FIREBASE_PROJECT_ID` | Firebase project ID |
| `FIREBASE_CLIENT_EMAIL` | Service-account `client_email` |
| `FIREBASE_PRIVATE_KEY` | Service-account `private_key`, including its BEGIN/END lines |
| `CLOUDFLARE_TURN_KEY_ID` | Cloudflare Realtime TURN key ID |
| `CLOUDFLARE_TURN_API_TOKEN` | Cloudflare Realtime TURN API token for that key |

Supabase supplies `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` to the function automatically.

Then configure Firebase on Android:

1. In Firebase, register an Android app with package name `com.example.tv_caller_app`.
2. Download its `google-services.json` into `app/google-services.json`. This file is intentionally ignored by Git.
3. Add `fcm_token` to `profiles` and verify that an authenticated user can update their own row under RLS.
4. Confirm the Firebase Cloud Messaging API is enabled. The Edge Function sends through FCM HTTP v1; no legacy server key belongs in the app.

Use high-priority **data-only** messages addressed to the exact signed-in user. Appointment messages may contain only `type`, `target_user_id`, and `appointment_id`; the details screen retrieves authoritative appointment data from the API. Call push messages are only wake-up hints. The authoritative SDP/ICE remains private in the durable signaling table and is fetched over authenticated HTTPS.

Managed TURN is required for dependable calls across carrier-grade NATs and many restricted Wi-Fi networks. Cloudflare returns UDP, TCP, and TLS/TCP relay choices, including TCP 443. No calling system can guarantee service on a network that blocks HTTPS, FCM, and TURN entirely.

### 5. Build and run

```bash
bash ./gradlew :app:assembleDebug
```
> Both devices must point at the same Supabase project.

## What works when the local web servers are off?

The Android app does not need the React frontend. The following assumes the device has Internet access and the Supabase/FCM/TURN setup above is deployed.

| Feature | Local backend needed? |
|---|---|
| Sign in/register, profile, contacts, favorites, presence | No — these use Supabase |
| Tablet-to-tablet audio/video calls, video upgrade, missed-call recovery | No — these use Supabase Edge/Realtime, FCM and TURN |
| Daily “I am okay” check-in | No — it writes directly to Supabase after the backend has linked the account as a patient |
| Existing appointment/profile data already held in the current process | It may remain visible briefly from memory |
| Load fresh appointments, accept/decline/propose a new time | **Yes** |
| Load medication, daily tasks and care team on Get help | **Yes** |
| Create/reschedule appointments and produce head-nurse alerts | **Yes** |
| Use the healthcare web portal | **Yes**, and the React frontend must also be running or hosted |

For unattended use, hosting the ASP.NET backend is therefore still necessary even though the calling path itself no longer depends on the local backend.

## Test on USB-connected tablets

The easiest option builds the current APK, updates every authorized tablet,
restores the backend tunnel for each one, and launches ViKom:

```bash
cd /path/to/vikom_app-main
./install-local-tablets.sh
```

Run it again whenever a tablet has rebooted or its USB/ADB connection has been
re-established. Android's reverse-port mappings are temporary and do not come
back merely because the backend and frontend were restarted.

Start the ViKom backend first:

```bash
cd /path/to/vikom-web-main
dotnet run --project backend --launch-profile http
```

If `backend.base.url` uses `127.0.0.1:5084`, forward that port separately for each USB-connected tablet, then build, install, and launch:

```bash
cd /path/to/vikom_app-main
adb devices -l
adb -s TABLET_SERIAL reverse tcp:5084 tcp:5084
bash ./gradlew :app:assembleDebug
adb -s TABLET_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s TABLET_SERIAL shell am start -W -n com.example.tv_caller_app/.ui.activities.AuthActivity
```

For two tablets, replace the placeholders with the two values printed by `adb devices -l`:

```bash
adb -s TABLET_SERIAL_1 reverse tcp:5084 tcp:5084
adb -s TABLET_SERIAL_2 reverse tcp:5084 tcp:5084
adb -s TABLET_SERIAL_1 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s TABLET_SERIAL_2 install -r app/build/outputs/apk/debug/app-debug.apk
```

`install -r` updates the app while retaining accounts and app data. Never expose Android ADB port 5555 to the public Internet. For tablets elsewhere, use a USB install, Google Play internal/closed testing, or managed-device deployment; wireless ADB is a local development tool, not an Internet update channel.

Repeat `adb reverse` after reconnecting or rebooting the tablet. Rebuild/reinstall after Android Kotlin or XML changes; restart the backend only after backend code/configuration changes. Changes in `vikom-web-main/frontend` do not require an Android or backend restart.

---

## Notes

- **Microphone-less devices:** If no microphone is detected, the app enters receive-only mode — the user can still receive and answer calls.
- **Background signaling:** `SignalingForegroundService` keeps live signaling available while Android permits it. Durable HTTPS polling and Firebase data messages provide the recovery/wake-up path once the Edge Function is configured.
- **Session security:** Sessions are stored in `EncryptedSharedPreferences` (AES256-GCM) and automatically refreshed in the foreground.
