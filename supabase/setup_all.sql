-- HumanSignal: tüm migration'lar tek dosyada — Supabase Dashboard → SQL Editor'e yapıştırıp Run.
-- Bir kez çalıştırılır. `npm run db:migrate` daha sonra bunları atlar (hs_schema_migrations kaydı).
begin;

create table if not exists public.hs_schema_migrations (name text primary key, applied_at timestamptz not null default now());
alter table public.hs_schema_migrations enable row level security;

-- ===================== 0001_init.sql =====================
-- HumanSignal Faz 1 şeması (SPEC.md §7, birebir).

create extension if not exists pgcrypto;

create table api_keys (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  key_hash    text not null unique,                 -- sha256 hex
  role        text not null check (role in ('app','admin')),
  created_at  timestamptz not null default now(),
  revoked_at  timestamptz
);

create table app_config (
  id              int primary key default 1 check (id = 1),
  config_version  int not null,
  config          jsonb not null,
  updated_at      timestamptz not null default now()
);

create table participants (
  id               uuid primary key default gen_random_uuid(),
  code             text not null unique check (code ~ '^P\d{3,5}$'),
  handedness       text check (handedness in ('left','right','both','unknown')),
  age_range        text check (age_range in ('18-24','25-34','35-44','45-54','55-64','65+','unknown')),
  gender           text,
  consent_version  text not null,
  consent_at       timestamptz not null default now(),
  created_at       timestamptz not null default now()
);

create table devices (
  id             uuid primary key default gen_random_uuid(),
  install_id     text not null unique,
  manufacturer   text,
  model          text,
  android_sdk    int,
  snapshot       jsonb not null,           -- §3.7 tam anlık görüntü (son hali)
  first_seen     timestamptz not null default now(),
  last_seen      timestamptz not null default now()
);

create table sessions (
  id                uuid primary key default gen_random_uuid(),
  local_session_id  text not null unique,
  participant_id    uuid references participants(id) on delete cascade,
  device_id         uuid not null references devices(id),
  label_kind        text not null check (label_kind in ('human','bot')),
  bot_tool          text check (bot_tool in ('adb_input','appium','uiautomator','espresso','maestro',
                                             'autoclicker_app','accessibility_app','scrcpy_remote','replay','other')),
  bot_host          text check (bot_host in ('emulator_android_studio','emulator_genymotion',
                                             'emulator_bluestacks','emulator_other','real_device')),
  placement         text not null check (placement in ('hand','table','holder','lap','other')),
  posture           text not null check (posture in ('sitting','standing','walking','lying','na')),
  hands             text not null check (hands in ('one_left','one_right','two','na')),
  notes             text,
  device_snapshot   jsonb not null,        -- oturum anındaki cihaz durumu (bütünlük sinyalleri dahil)
  clock             jsonb not null,
  sdk_version       text not null,
  app_version       text,
  config_version    int,
  status            text not null default 'open' check (status in ('open','incomplete','complete')),
  stop_reason       text,
  total_chunks      int,
  received_chunks   int not null default 0,
  stats             jsonb,
  started_at        timestamptz not null default now(),
  ended_at          timestamptz,
  constraint human_needs_participant check (label_kind = 'bot' or participant_id is not null),
  constraint bot_needs_tool check (label_kind = 'human' or (bot_tool is not null and bot_host is not null))
);
create index on sessions (label_kind, bot_tool);
create index on sessions (participant_id);
create index on sessions (started_at);

create table chunks (
  session_id    uuid not null references sessions(id) on delete cascade,
  seq           int  not null check (seq >= 0),
  storage_path  text not null,
  size_bytes    int  not null,
  sha256        text not null,
  t_start_ns    bigint not null,
  t_end_ns      bigint not null,
  streams       text[] not null,
  records       int,
  received_at   timestamptz not null default now(),
  primary key (session_id, seq)
);

create table audit_log (
  id          bigserial primary key,
  at          timestamptz not null default now(),
  action      text not null,
  detail      jsonb
);

-- Tüm tablolarda RLS açık, hiçbir politika yok:
-- anon/authenticated rolleri hiçbir şey göremez; yalnızca service role (API) erişir.
alter table api_keys      enable row level security;
alter table app_config    enable row level security;
alter table participants  enable row level security;
alter table devices       enable row level security;
alter table sessions      enable row level security;
alter table chunks        enable row level security;
alter table audit_log     enable row level security;

-- received_chunks sayacını atomik artırmak için
create or replace function increment_received(p_session uuid) returns void
language sql as $$
  update sessions set received_chunks = received_chunks + 1 where id = p_session;
$$;

insert into public.hs_schema_migrations(name) values ('0001_init.sql') on conflict do nothing;

-- ===================== 0002_storage_and_seed.sql =====================
-- Storage bucket (§7: private "raw-chunks") ve app_config tek satırı (§6.4 GET /v1/config).

insert into storage.buckets (id, name, public, file_size_limit)
values ('raw-chunks', 'raw-chunks', false, 5242880)
on conflict (id) do nothing;

insert into app_config (id, config_version, config) values (1, 1, '{
  "config_version": 1,
  "chunk_seconds": 5,
  "max_chunk_bytes": 5242880,
  "sensors": {
    "acc":   {"enabled": true, "rate_hz": 100},
    "acc_u": {"enabled": true, "rate_hz": 100},
    "gyr":   {"enabled": true, "rate_hz": 100},
    "gyr_u": {"enabled": true, "rate_hz": 100},
    "mag":   {"enabled": true, "rate_hz": 50},
    "mag_u": {"enabled": true, "rate_hz": 50},
    "grav":  {"enabled": true, "rate_hz": 50},
    "lin":   {"enabled": true, "rate_hz": 100},
    "rot":   {"enabled": true, "rate_hz": 50},
    "grot":  {"enabled": true, "rate_hz": 50},
    "light": {"enabled": true},
    "prox":  {"enabled": true},
    "press": {"enabled": true, "rate_hz": 10}
  },
  "upload": {"wifi_only": false, "max_local_mb": 200}
}'::jsonb)
on conflict (id) do nothing;

insert into public.hs_schema_migrations(name) values ('0002_storage_and_seed.sql') on conflict do nothing;

-- ===================== 0003_grants.sql =====================
-- Erişim: yalnızca service role (API). RLS zaten açık ve politika yok (§7); bu dosya ikinci bir katman ekler
-- ve yeni Supabase projelerinde tabloların service_role'e açık olmasını garanti eder.

grant usage on schema public to service_role;
grant select, insert, update, delete on all tables in schema public to service_role;
grant usage, select on all sequences in schema public to service_role;
grant execute on function public.increment_received(uuid) to service_role;

revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
-- Fonksiyonlar varsayılan olarak PUBLIC'e açıktır; REST üzerinden anon çağıramasın.
revoke execute on function public.increment_received(uuid) from public, anon, authenticated;

insert into public.hs_schema_migrations(name) values ('0003_grants.sql') on conflict do nothing;

revoke all on public.hs_schema_migrations from anon, authenticated;

commit;
