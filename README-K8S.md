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
| `ci.yml` | PR + push main | **Lint gate** (Checkstyle + ESLint) → `mvn verify` (Testcontainers → PostgreSQL reale) + build immagine; su main pubblica su **GHCR** tagata col SHA | GitHub-hosted |
| `cd.yml` | push main | cluster **kind effimero** (1 CP + 2 workers) → PostgreSQL 16 (primary + 2 replica) → build → `kind load` → rollout → seed demo → smoke test su `:8080` → **E2E Playwright** → rollback best-effort (`rollout undo`) su fallimento | GitHub-hosted |

> **Perché più self-hosted runner?** Prima il CD girava su un runner self-hosted
> (`cachyos-x8664`): un PR di un contributor avrebbe potuto eseguire codice
> arbitrario su questa macchina. Ora ogni deploy nasce un cluster **kind
> effimero** su un runner `ubuntu-latest` di GitHub: il codice di terzi gira
> solo in sandbox di GitHub, il cluster viene smontato a fine run.

### E2E Playwright (S5)

Il CD esegue i **test E2E** dopo il deploy (post-smoke):

- **Chromium** (Playwright 1.55) scaricato in `~/.cache/ms-playwright` con
  **cache GitHub Actions** riutilizzata tra i run del progetto
- Test **gated**: `@EnabledIfSystemProperty("e2e.enabled")` — `mvn verify` in CI **non li esegue**
  (nessun cluster); in CD partono con
  `-De2e.enabled=true -De2e.baseUrl=http://localhost:8080`
- Base URL = **frontend** (`:8080`, host-port mapping del cluster kind):
  i test coprono la catena completa browser → nginx → API → PostgreSQL
- Credenziali admin: secret **`E2E_ADMIN_PASSWORD`** (iniettato anche nel seed,
  così E2E e dati demo sono coerenti; fallback dev `admin123`)
- Fallimento E2E → **rollback best-effort** + upload dei report Surefire come artifact

### Secrets (GitHub → Settings → Secrets and variables → Actions)

| Secret | A cosa serve | Fallback dev |
|--------|-------------|--------------|
| `ESHOP_DB_PASSWORD` | Password PostgreSQL (secret K8s `eshop-secret`) | `eshop123` |
| `ESHOP_JWT_SECRET` | Firma JWT (32 byte hex) | default nel `Makefile` |
| `E2E_ADMIN_PASSWORD` | Password utente `admin` (seed + E2E) | `admin123` |

I fallback dev esistono solo perché il repo resti runnable da chiunque:
in un deploy reale i secret vanno definiti.

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
