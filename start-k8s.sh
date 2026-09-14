#!/usr/bin/env bash
# =============================================================================
# E-Shop — avvio con il nuovo stack (Kubernetes/kind + nginx + PostgreSQL)
#
#   ./start-k8s.sh
#
# Idempotente: crea il cluster e applica i manifesti solo se non ci sono,
# builda l'immagine, deploya le 3 repliche e attende che l'ingresso risponda.
#
# Fine: http://localhost:8080
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

# --- 0) tooling ---------------------------------------------------------------
missing=()
for tool in docker kind kubectl make; do
    command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
done
if [ ${#missing[@]} -gt 0 ]; then
    echo "❌ tool mancanti: ${missing[*]}   (Arch: paru -S kind kubectl)" >&2
    exit 1
fi

# --- 1) cluster kind -----------------------------------------------------------
if ! kubectl get nodes >/dev/null 2>&1; then
    echo "🏗️  creo il cluster kind..."
    make cluster
fi

# --- 2) dipendenze: namespace, secret, configmap, postgres, nginx --------------
if ! kubectl get ns eshop >/dev/null 2>&1 || ! kubectl -n eshop get deploy eshop >/dev/null 2>&1; then
    echo "📦 applico namespace + secret + postgres + nginx..."
    make deps
fi

# --- 3) build immagine + deploy app ---------------------------------------------
make deploy

# --- 4) attesa ingresso HTTP ----------------------------------------------------
echo "⏳ attendo http://localhost:8080 ..."
code=""
for i in $(seq 1 30); do
    code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/ || true)
    [ "$code" = "200" ] && break
    sleep 2
done
if [ "$code" = "200" ]; then
    echo "✅ E-Shop online → http://localhost:8080"
    kubectl -n eshop get pods
else
    echo "❌ l'app non risponde (http ${code:-n/a}) — make logs / kubectl -n eshop get pods" >&2
    exit 1
fi
