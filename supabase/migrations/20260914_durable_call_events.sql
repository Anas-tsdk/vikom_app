-- Durable, private fallback for ViKom WebRTC signaling.
--
-- Production call messages are written here by the call-infrastructure Edge
-- Function and recovered over authenticated HTTPS. Public Realtime broadcast
-- remains only as a local-development fallback while this function is absent.

create table if not exists public.call_signaling_events (
    id bigint generated always as identity primary key,
    call_id text not null,
    sender_id text not null,
    recipient_id text not null,
    event_type text not null check (event_type in (
        'call_offer',
        'call_answer',
        'ice_candidate',
        'call_rejected',
        'call_ended',
        'camera_state',
        'video_upgrade_request',
        'video_upgrade_response',
        'call_answered_elsewhere'
    )),
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '7 days'),
    consumed_at timestamptz
);

create index if not exists ix_call_signaling_events_recipient_pending
    on public.call_signaling_events (recipient_id, id)
    where consumed_at is null;

create index if not exists ix_call_signaling_events_expiry
    on public.call_signaling_events (expires_at);

alter table public.call_signaling_events enable row level security;

-- Clients never access this table directly. The Edge Function authenticates
-- either the Supabase patient token or the portal's independently-issued JWT,
-- then uses the service role for the narrowly validated insert/poll/ack.
revoke all on table public.call_signaling_events from anon, authenticated;
grant all on table public.call_signaling_events to service_role;

comment on table public.call_signaling_events is
    'Short-lived durable fallback for authenticated ViKom call signaling.';
