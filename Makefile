# =============================================================================
# E-Shop — Kubernetes locale (kind)
#   make cluster   → crea il cluster kind
#   make deploy    → build immagini + applica manifesti
#   make logs      → segue i log
#   make rollback  → undo dell'ultimo rollout
#   make seed      → dati demo idempotenti (immagini + admin + catalogo)
#   make down      → smonta tutto
# =============================================================================
NS       := eshop
APP      := eshop
API_IMG  := eshop-api:local
WEB_IMG  := eshop-web:local
K        := kubectl -n $(NS)

# Secret di runtime. I default valgono solo per lo sviluppo locale:
# in CI/CD vengono sovrascritti da variabili d'ambiente (GitHub Secrets).
DB_PASSWORD  ?= eshop123
JWT_SECRET   ?= 404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970
ADMIN_PASSWORD ?= admin123

.PHONY: cluster deps build deploy deploy-loaded status logs rollback down seed

configmap-web: ## ConfigMap frontend sempre in sync con frontend/nginx.conf (+ rollout solo se cambia)
	@kubectl create configmap frontend-config -n $(NS) --from-file=nginx.conf=frontend/nginx.conf --dry-run=client -o yaml | $(K) apply -f -
	@h=$$($(K) get cm frontend-config -o jsonpath='{.data.nginx\.conf}' | sha256sum | cut -c1-16); \
	 kubectl -n $(NS) patch deploy frontend --type merge -p "{\"spec\":{\"template\":{\"metadata\":{\"annotations\":{\"config.kubernetes.io/checksum\":\"$$h\"}}}}}"

cluster:
	kind create cluster --config kind/cluster.yaml

deps: ## namespace + secret + configmap + postgres (primary + 2 replica) + frontend
	kubectl apply -f k8s/namespace.yaml
	# Secret generati in modo dichiarativo (mai committati)
	kubectl -n $(NS) create secret generic eshop-secret \
		--from-literal=db-user=eshop \
		--from-literal=db-password=$(DB_PASSWORD) \
		--from-literal=jwt-secret=$(JWT_SECRET) \
		--dry-run=client -o yaml | $(K) apply -f -
	$(K) apply -f k8s/configmap.yaml
	$(K) apply -f k8s/postgres.yaml
	$(K) apply -f k8s/postgres-replicas.yaml
	# frontend PRIMA di configmap-web: la patch di checksum richiede che
	# il deployment esista (cluster fresh: non esiste ancora)
	$(K) apply -f k8s/frontend.yaml
	$(MAKE) configmap-web
	# Aspetta che il DB sia pronto
	@for i in $$(seq 1 60); do $(K) get pods -l app.kubernetes.io/name=eshop-db -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null | grep -q true && break; sleep 2; done; echo "DB ready"
	# Streaming replication: abilita le connessioni replication in pg_hba.conf (idempotente)
	@$(K) exec eshop-db-0 -- su postgres -c "HBA=/var/lib/postgresql/data/pg_hba.conf; grep -q '^host replication' \$$HBA || echo 'host replication all all scram-sha-256' >> \$$HBA; psql -U eshop -d eshop -c 'SELECT pg_reload_conf()';" >/dev/null 2>&1 || true

build:
	docker build -t $(API_IMG) .
	docker build -t $(WEB_IMG) -f frontend/Dockerfile frontend/

deploy: ## da eseguire DOPO cluster + deps (build + load + apply)
	$(MAKE) build
	kind load docker-image $(API_IMG) --name eshop
	kind load docker-image $(WEB_IMG) --name eshop
	@$(MAKE) deploy-loaded

deploy-loaded: ## apply + rollout SENZA rebuild (immagini già caricate in kind)
	$(K) apply -f k8s/app.yaml
	$(K) apply -f k8s/app-service.yaml
	@echo "⏳ rollout backend..."
	$(K) rollout status deploy/$(APP) --timeout=180s
	$(MAKE) configmap-web
	$(K) apply -f k8s/frontend.yaml
	@echo "⏳ rollout frontend..."
	$(K) rollout status deploy/frontend --timeout=120s
	@echo "✅ E-Shop su http://localhost:8080  (frontend NodePort 30080)"

status:
	$(K) get pods,svc,deploy -o wide

logs:
	$(K) logs -l app.kubernetes.io/name=eshop -f

seed: ## dati demo idempotenti: immagini + admin + catalogo
	@ADMIN_PASSWORD=$(ADMIN_PASSWORD) bash seed.sh

rollback:
	$(K) rollout undo deploy/$(APP)

down:
	$(K) delete all --all 2>/dev/null || true
	$(K) delete pvc --all 2>/dev/null || true
	kubectl delete ns eshop 2>/dev/null || true
	kind delete cluster --name eshop 2>/dev/null || true
