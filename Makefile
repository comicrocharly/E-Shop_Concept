# =============================================================================
# E-Shop — Kubernetes locale (kind)
#   make cluster   → crea il cluster kind
#   make deploy    → build immagine + applica manifesti
#   make logs      → segue i log
#   make rollback  → undo dell'ultimo rollout
#   make down      → smonta tutto
# =============================================================================
NS      := eshop
APP     := eshop
IMG     := eshop:local
K       := kubectl -n $(NS)

.PHONY: cluster deps build deploy status logs rollback down

cluster:
	kind create cluster --config kind/cluster.yaml

deps: ## namespace + secret + configmap + postgres
	kubectl apply -f k8s/namespace.yaml
	# Secret generati in modo dichiarativo (mai committati)
	kubectl -n $(NS) create secret generic eshop-secret \
		--from-literal=db-user=eshop \
		--from-literal=db-password=eshop123 \
		--from-literal=jwt-secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970 \
		--dry-run=client -o yaml | $(K) apply -f -
	$(K) apply -f k8s/configmap.yaml
	$(K) apply -f k8s/postgres.yaml
	$(K) apply -f k8s/nginx.yaml
	# Aspetta che il DB sia pronto
	@for i in $$(seq 1 60); do $(K) get pods -l app.kubernetes.io/name=eshop-db -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null | grep -q true && break; sleep 2; done; echo "DB ready"

build:
	docker build -t $(IMG) .

deploy: ## da eseguire DOPO cluster + deps
	$(MAKE) build
	kind load docker-image $(IMG) --name eshop
	$(K) apply -f k8s/app.yaml
	$(K) apply -f k8s/app-service.yaml
	@echo "⏳ rollout..."
	$(K) rollout status deploy/$(APP) --timeout=180s
	@echo "✅ E-Shop su http://localhost:8080  (nginx NodePort 3080 → host 8080)"

status:
	$(K) get pods,svc,deploy -o wide

logs:
	$(K) logs -l app.kubernetes.io/name=eshop -f

rollback:
	$(K) rollout undo deploy/$(APP)

down:
	$(K) delete all --all 2>/dev/null || true
	kubectl delete ns eshop 2>/dev/null || true
	kind delete cluster --name eshop 2>/dev/null || true
