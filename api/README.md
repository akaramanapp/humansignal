# HumanSignal API (M3)

Node.js 20+/22, TypeScript (strict), Fastify 5, zod 4, `@supabase/supabase-js` (service role, yalnızca sunucuda).

```bash
npm install
npm test                 # vitest + Fastify inject (bellek içi backend)
npm run typecheck
npm run build && npm start
```

## Yerel geliştirme (Supabase olmadan)

```bash
DATA_BACKEND=memory DEV_API_KEYS=app:dev-app-key,admin:dev-admin-key PORT=8787 npm run dev
```

Veri yalnızca RAM'dedir. Emülatörden adres `http://10.0.2.2:8787` (research-app debug varsayılanı).
Not: 8080 bu makinede başka bir konteyner tarafından kullanılıyor; `.env` içinde `PORT` ayarlanabilir.

## Supabase ile

1. `.env.example` → `.env`: `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` ve (yalnızca migration için) `SUPABASE_DB_URL` (Dashboard → Connect → Session pooler).
2. Migration'lar: `npm run db:migrate` (uygulananlar `hs_schema_migrations`'ta; tekrar çalıştırmak güvenli).
   Bağlantı dizesi yoksa `supabase/setup_all.sql` SQL Editor'de bir kez çalıştırılır.
   - `0001_init.sql` §7 birebir · `0002_storage_and_seed.sql` private `raw-chunks` bucket + `app_config` · `0003_grants.sql` yalnızca service_role erişimi
3. Anahtar (DB'de yalnızca SHA-256): `npm run key:create -- --name research-app --role app --write-properties ../android/local.properties`
   (`--write-properties` anahtarı ekrana yazmaz; research-app derlemesi buradan okur.)
4. Doğrulama: `npm run check:setup` · Bir oturumu okuyup parçaları doğrulamak: `npm run inspect -- [session_id]`
5. A10 kabul testi (çalışan API'ye karşı; P999/P998 test katılımcılarını oluşturup siler):
   `HS_APP_KEY=… HS_ADMIN_KEY=… npm run a10` · Anahtar iptali: `npm run key:revoke -- --id <uuid>`

## Uçlar

| Uç | Rol | Not |
|---|---|---|
| `GET /health` | — | DB erişilemezse 503 |
| `GET /v1/config` | app | `app_config` satırı, yoksa gömülü varsayılan |
| `POST /v1/sessions` | app | `local_session_id` ile idempotent (201 / 200) |
| `PUT /v1/sessions/:id/chunks/:seq` | app | ham gzip, `X-Chunk-*` başlıkları; duplicate 200, conflict 409 |
| `POST /v1/sessions/:id/complete` | app | eksik `seq` listesi; eksikler gelince otomatik `complete` |
| `GET /v1/admin/sessions` | admin | filtreler + keyset `cursor`, `limit ≤ 500` |
| `GET /v1/admin/sessions/:id/chunks` | admin | 1 saatlik imzalı URL'ler |
| `DELETE /v1/admin/participants/:code` | admin | Storage + cascade silme, `audit_log` (kişisel veri yok) |
| `POST /v1/score` | app | 501 (Faz 2) |

### Spesifikasyondan farklar / eklemeler

- `complete` gövdesi isteğe bağlı `clock` alanı kabul eder: SDK oturum sonunda kesinleşen `sensor_clock`'u gönderir, `sessions.clock` ile birleştirilir.
- `admin` anahtarı app uçlarını da çağırabilir.
- Katılımcı bulunamazsa `404 PARTICIPANT_NOT_FOUND`; bilinmeyen rota `404 NOT_FOUND`.
- Rate limit anahtarı, API anahtarının SHA-256 özetinin ilk 16 karakteridir (IP kullanılmaz, anahtarın kendisi bellekte tutulmaz).
- Silme sırasında Storage klasörü de listelenir; DB satırı olmayan yarım kalmış nesneler de silinir.
