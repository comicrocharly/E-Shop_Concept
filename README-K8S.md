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
             │  frontend (3 replica, NodePort 30080)        │
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
make cluster    # 1. crea il cluster kind (kind-eshop-*)
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
| `cd.yml` | push main | build immagine locale → `kind load` → `set image` → `rollout status` → smoke test su `:8080` → **rollback automatico** su fallimento | **self-hosted** `eshop-dev` |

### Setup runner (una tantum, su questa macchina)

1. Repo GitHub → **Settings → Actions → Self-hosted runners → New self-hosted runner**
2. Copia i 3 comandi che genera (o in alternativa):
   ```bash
   # scarica l'archivio del runner, poi registra con il token della repo:
   ./config.sh --url https://github.com/comicrocharly/E-Shop_Concept \
               --token <token> --name eshop-dev --labels eshop-dev
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
