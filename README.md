# Vikom

An Android TV audio and video calling app with contact management, quick dial, and AI assistant integration.

**Platform:** Android TV (min SDK 25, target SDK 36) | **Language:** Kotlin 2.0.21 | **Architecture:** MVVM

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Supabase (PostgreSQL + Auth + Realtime + Edge Functions) v2.6.1 |
| Calling | WebRTC peer-to-peer audio/video (Stream WebRTC Android v1.3.8) |
| Signaling | Supabase Realtime (WebSocket) |
| UI | Android Leanback v1.2.0 (TV-optimized), Jetpack Fragments/LiveData/ViewModel |
| Networking | Ktor HTTP client v2.3.12 + OkHttp engine |
| Image Loading | Glide v4.11.0 |
| Security | EncryptedSharedPreferences (AES256-GCM), Supabase RLS, JWT auth |
| Serialization | Kotlin Serialization v1.6.3 |
| Async | Kotlin Coroutines v1.8.1 |

---

## Features

### Calling
- WebRTC peer-to-peer audio and video calls, with hybrid microphone support (built-in, USB, Bluetooth; falls back to receive-only mode if no mic is available)
- Full call state machine: Idle → Initiating → Ringing → Connecting → Connected → Ended
- Full-screen incoming call alerts with screen wake-lock and lock-screen display
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
```

### 3. Supabase database

Create the following tables:

| Table | Key columns |
|---|---|
| `profiles` | `id` (UUID), `username`, `email`, `avatar_url`, `contact_id` (INT), `is_online`, `last_seen`, `device_type`, `webrtc_status`, `has_seen_onboarding` |
| `contacts` | `id`, `user_id`, `contact_id` (INT), `email`, `address`, `notes`, `nickname`, `is_favorite` |
| `call_history` | `id`, `user_id`, `contact_id` (INT), `contact_name`, `call_type`, `call_method`, `media_type`, `call_duration`, `call_timestamp`, `connection_quality`, `ice_connection_state`, `end_reason`, `notes` |
| `priority_contacts` | `user_id`, `slot` (INT), `contact_id` |

Enable Row-Level Security (RLS) on all tables.

### 4. Build and run

```bash
./gradlew assembleDebug
```
> Both devices must point at the same Supabase project.

---

## Notes

- **Microphone-less devices:** If no microphone is detected, the app enters receive-only mode — the user can still receive and answer calls.
- **Background signaling:** `SignalingForegroundService` keeps the WebSocket alive when the app is backgrounded. `BootReceiver` restarts it after a device reboot.
- **Session security:** Sessions are stored in `EncryptedSharedPreferences` (AES256-GCM) and automatically refreshed in the foreground.
