-- Run once in Supabase SQL Editor before deploying the Android registration update.
-- Existing accounts remain patients; only people who explicitly opt out are excluded.
alter table public.profiles
    add column if not exists is_patient boolean not null default true;

update public.profiles as profile
set is_patient = coalesce(
    (select (auth_user.raw_user_meta_data ->> 'is_patient')::boolean
     from auth.users as auth_user
     where auth_user.id = profile.id),
    true
);

create or replace function public.apply_profile_patient_eligibility()
returns trigger
language plpgsql
security definer
set search_path = public, auth
as $$
begin
    new.is_patient := coalesce(
        (select (auth_user.raw_user_meta_data ->> 'is_patient')::boolean
         from auth.users as auth_user
         where auth_user.id = new.id),
        true
    );
    return new;
end;
$$;

drop trigger if exists profiles_apply_patient_eligibility on public.profiles;
create trigger profiles_apply_patient_eligibility
before insert on public.profiles
for each row execute function public.apply_profile_patient_eligibility();

create or replace function public.sync_profile_patient_eligibility()
returns trigger
language plpgsql
security definer
set search_path = public, auth
as $$
begin
    update public.profiles
    set is_patient = coalesce((new.raw_user_meta_data ->> 'is_patient')::boolean, true)
    where id = new.id;
    return new;
end;
$$;

drop trigger if exists auth_sync_profile_patient_eligibility on auth.users;
create trigger auth_sync_profile_patient_eligibility
after insert or update of raw_user_meta_data on auth.users
for each row execute function public.sync_profile_patient_eligibility();

comment on column public.profiles.is_patient is
    'False when the account owner opted out of patient care features during registration.';
