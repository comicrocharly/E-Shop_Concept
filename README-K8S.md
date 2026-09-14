# E-Shop — Kubernetes (dev locale)

Deployment dell'applicazione su **kind** (Kubernetes in Docker) con **nginx**
come punto d'ingresso e **CI/CD** via GitHub Actions.

## Architettura

```
 browser ──► http://localhost:8080
                │  (kind host-port → NodePort 3080)
             ┌──▼────────────────────────────────────────────┐
             │ namespace eshop                               │
             │  nginx (2 replica, NodePort 3080)            │
             │     │  proxy →                              │
             │  Service eshop-api:8081  ◄── load balancing │
             │     │  (kernel K8s: solo pod ready)         │
             │  ┌────────┬────────┬────────┐               │
             │  │ pod 1  │ pod 2  │ pod 3  │  Deployment 3 │
             │  └────────┴────────┴────────┘               │
             │     │                                │       │
             │  eshop-db (StatefulSet + PVC 2Gi)   │       │
             └──────────────────────────────────────────────┘
```

**Self-healing attivo su ogni livello:**
- pod app crashato → Deployment lo ricrea; `livenessProbe` lo riavvia se impalla
- pod non pronto → `readinessProbe` lo tiene fuori dal traffic finché non è ready
- nginx replica down → Service nginx bilancia sull'altra
- PostgreSQL down → `livenessProbe` lo riavvia, dati sul PVC
- rollout fallito → CD fa `rollout undo` automatico

## Uso

```bash
make cluster    # 1. crea il cluster kind (kind-eshop-*)
make deps       # 2. namespace, secret, configmap, postgres, nginx
make deploy     # 3. build + load immagine + deployment → http://localhost:8080
make status     # stato
make logs       # log app in tempo reale
make rollback   # undo ultimo rollout
make down       # smonta tutto
```

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

- **Immagini articoli**: volume `hostPath` condiviso (sufficiente su kind).
  In produzione: PVC RWX (NFS) o refactor `StorageService` S3/MinIO per rendere
  i pod stateless e scalare su più zone.
- **ddl-auto=update**: ok in dev. In produzione passare a Flyway + `validate`.
- **Rate limiting** in-memory: il limite è per-pod (con 3 repliche è 3×).
  Se serve esatto: Redis.
- **Secret**: `make deps` li genera nel cluster (non in Git). In produzione:
  Vault / Sealed Secrets / cloud secret manager.
