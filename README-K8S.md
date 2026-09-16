# E-Shop — Kubernetes (dev locale)

Deployment dell'applicazione su **kind** (Kubernetes in Docker) con
**frontend** e **backend** in container separati (ridondati) e **CI/CD**
via GitHub Actions.

## Architettura

```
 browser ──► http://localhost:8080
                │  (kind host-port → NodePort 30080)
             ┌──▼────────────────────────────────────────────┐
             │ namespace eshop                               │
             │  frontend (3 repliche, NodePort 30080)       │
             │   • nginx statico: index.html + asset         │
             │   • proxy /api/ + /images/ →                │
             │  Service eshop-api:8081  ◄── load balancing  │
             │     │  (kernel K8s: solo pod ready)          │
             │  ┌────────┬────────┬────────┐                │
             │  │ pod 1  │ pod 2  │ pod 3  │  Deployment 3  │
             │  └────────┴────────┴────────┘  (backend API) │
             │     │                                        │
             │  eshop-db primary (StatefulSet + PVC)         │
             │     │  WAL streaming                          │
             │  eshop-db-replica ×2 (standby read-only)      │
             └──────────────────────────────────────────────┘
```

**Immagini:**
- `eshop-api` — backend Spring Boot (multi-stage Maven→JRE 21), solo API
- `eshop-web` — frontend nginx statico (~5 MB), proxy `/api/` → backend

**Self-healing attivo su ogni livello:**
- pod app/frontend crashato → Deployment lo ricrea; `livenessProbe` lo riavvia se impalla
- pod non pronto → `readinessProbe` lo tiene fuori dal traffic finché non è ready
- replica frontend/app down → il Service bilancia sulle altre
- PostgreSQL primary down → `livenessProbe` lo riavvia, dati sul PVC
- PostgreSQL replica ×2: standby in streaming; se il primary muore, un
  replica viene promosso (dev: si rimuove `standby.signal` e si riavvia)
- rollout fallito → CD fa `rollout undo` automatico

## Uso

```bash
make cluster    # 1. crea il cluster kind (nome: eshop)
make deps       # 2. namespace, secret, configmap, postgres (primary + 2 replica), frontend
make deploy     # 3. build immagini + load + deployment → http://localhost:8080
make status     # stato
make logs       # log app in tempo reale
make rollback   # undo ultimo rollout
make seed       # dati demo idempotenti: immagini + admin + catalogo
make down       # smonta tutto
```

### Dati demo (seed)

`make seed` (chiamato automaticamente da `./start-k8s.sh`) è **idempotente**:

- genera **9 articoli demo** (Audio / Wearables / Accessories) con immagini SVG,
  copiate nel volume hostPath `/host/eshop-article-images` su **tutti** i node
  kind (servite dal backend su `/images/articles/**`)
- crea l'utente **`admin` / `admin123`** (ruolo `ADMIN`, via endpoint pubblico
  `POST /api/auth/register`)

Dopo un `make down` + `./start-k8s.sh` il catalogo si ripopola da solo.

### Test manuale del self-healing

```bash
# uccidi una replica → viene riacchiata in ~5s e torna nel load balancing
kubectl -n eshop delete pod -l app.kubernetes.io/name=eshop --field-selector metadata.name=$(kubectl -n eshop get pods -l app.kubernetes.io/name=eshop -o jsonpath='{.items[0].metadata.name}')
kubectl -n eshop get pods -w
```

```bash
# rollout manuale: cambia tag e guarda il rolling update
kubectl -n eshop set image deployment/eshop eshop=eshop:v2
kubectl -n eshop rollout status deployment/eshop
```

## CI/CD

| Workflow | Quando | Cosa fa | Runner |
|---|---|---|---|
| `ci.yml` | PR + push main | `mvn verify` (Testcontainers → PostgreSQL reale) + build immagine; su main pubblica su **GHCR** tagata col SHA | GitHub-hosted |
| `cd.yml` | push main | build immagini (backend + frontend) → `kind load` → `set image` → `rollout status` → smoke test su `:8080` → **E2E Playwright (12 test)** → **rollback automatico** (`rollout undo`) su qualsiasi fallimento | **self-hosted** `cachyos-x8664` |

### E2E Playwright (S5) — prerequisiti sul runner

Il CD esegue i **12 test E2E** dopo il deploy (post-smoke):

- **Chromium** già in `~/.cache/ms-playwright` (Playwright Java 1.55, revisione 1187);
  se manca, il workflow fa fallback `npx playwright install chromium` prima dei test
- Test **gated**: `@EnabledIfSystemProperty("e2e.enabled")` — `mvn verify` in CI **non li esegue**
  (runner GitHub-hosted senza cluster); in CD partono con
  `-De2e.enabled=true -De2e.baseUrl=http://localhost:8080`
- Base URL = **frontend** (`:8080`): i test coprono la catena completa
  browser → nginx → API → PostgreSQL
- Credenziali admin: env `E2E_ADMIN_USERNAME`/`E2E_ADMIN_PASSWORD`
  (fallback `admin`/`admin123`, i dati del seed), override via repo vars/secrets
- Fallimento E2E → **rollback automatico** (come per il smoke test)

### Setup runner (una tantum, su questa macchina)

1. Repo GitHub → **Settings → Actions → Self-hosted runners → New self-hosted runner**
2. Copia i 3 comandi che genera (o in alternativa):
   ```bash
   # scarica l'archivio del runner, poi registra con il token della repo:
   ./config.sh --url https://github.com/comicrocharly/E-Shop_Concept \
               --token <token> --name cachyos-x8664 --labels cachyos-x8664
   ./run.sh   # tenere in foreground (o systemd/autostart)
   ```
3. Il runner vede `docker`, `kind`, `kubectl` → il CD gira direttamente
   sul cluster locale.

## Note / limiti dev

- **PostgreSQL replica ×2**: standby read-only in streaming (non sono un
  failover automatico; la promozione in dev è manuale). In produzione:
  Patroni o managed DB (RDS/Cloud SQL).
- **Immagini articoli**: volume `hostPath` condiviso (sufficiente su kind).
  In produzione: PVC RWX (NFS) o refactor `StorageService` S3/MinIO per rendere
  i pod stateless e scalare su più zone.
- **ddl-auto=update**: ok in dev. In produzione passare a Flyway + `validate`.
- **Rate limiting** in-memory: il limite è per-pod (con 3 repliche è 3×).
  Se serve esatto: Redis.
- **Secret**: `make deps` li genera nel cluster (non in Git). In produzione:
  Vault / Sealed Secrets / cloud secret manager.
