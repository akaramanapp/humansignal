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
