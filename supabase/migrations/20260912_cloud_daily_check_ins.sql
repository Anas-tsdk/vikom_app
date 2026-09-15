-- Cloud-backed daily wellbeing check-ins.
--
-- A row exists only for a Supabase account currently linked to a patient in
-- the ViKom web application. The Android app can therefore determine whether
-- the check-in gate applies without reaching the developer's local .NET API.

-- Push registration already uses this profile field. Keep it in the same
-- deployment because older projects may not have created it yet.
alter table public.profiles
    add column if not exists fcm_token text;

comment on column public.profiles.fcm_token is
    'Firebase Cloud Messaging token for the signed-in patient device.';

create table if not exists public.patient_daily_check_ins (
    profile_id uuid primary key references auth.users(id) on delete cascade,
    started_at timestamptz not null default now(),
    last_check_in_at timestamptz,
    last_check_in_source text check (last_check_in_source in ('Patient', 'Personnel')),
    updated_at timestamptz not null default now()
);

alter table public.patient_daily_check_ins enable row level security;

drop policy if exists "Patients can read their own daily check-in" on public.patient_daily_check_ins;
create policy "Patients can read their own daily check-in"
on public.patient_daily_check_ins
for select
to authenticated
using (profile_id = (select auth.uid()));

-- The client receives read access only. Confirmations go through the function
-- below so a tablet cannot choose its own timestamp or modify another account.
revoke all on table public.patient_daily_check_ins from anon;
revoke insert, update, delete on table public.patient_daily_check_ins from authenticated;
grant select on table public.patient_daily_check_ins to authenticated;
grant all on table public.patient_daily_check_ins to service_role;

create or replace function public.confirm_daily_check_in()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception 'Authentication is required' using errcode = '28000';
    end if;

    update public.patient_daily_check_ins
    set last_check_in_at = now(),
        last_check_in_source = 'Patient',
        updated_at = now()
    where profile_id = auth.uid();

    if not found then
        raise exception 'Daily check-in is not enabled for this account' using errcode = 'P0002';
    end if;
end;
$$;

revoke all on function public.confirm_daily_check_in() from public;
revoke all on function public.confirm_daily_check_in() from anon;
grant execute on function public.confirm_daily_check_in() to authenticated;

comment on table public.patient_daily_check_ins is
    'Cloud source of truth for the patient-only ViKom daily wellbeing check-in.';
