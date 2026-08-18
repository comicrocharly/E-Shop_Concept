#!/bin/bash
# =============================================================================
# E-Shop Quick Start
# Usage: ./start.sh
# =============================================================================

cd "$(dirname "$0")"

# ── 1. PostgreSQL container ──────────────────────────────────────────────────
# Check running containers first, then stopped ones (docker ps -a)
if ! docker ps -a --format '{{.Names}}' | grep -q '^eshop-postgres$'; then
    echo "🐘 Starting PostgreSQL container..."
    docker run -d --name eshop-postgres \
        -e POSTGRES_USER=eshop \
        -e POSTGRES_PASSWORD=eshop123 \
        -e POSTGRES_DB=eshop \
        -p 5432:5432 \
        postgres:16
else
    STATUS=$(docker inspect -f '{{.State.Status}}' eshop-postgres 2>/dev/null || echo "nonexistent")
    if [ "$STATUS" = "exited" ]; then
        echo "🐘 Starting stopped PostgreSQL container..."
        docker start eshop-postgres
    elif [ "$STATUS" = "running" ]; then
        echo "🐘 PostgreSQL already running"
    else
        echo "🐘 PostgreSQL container in unexpected state: $STATUS — removing and recreating..."
        docker rm -f eshop-postgres 2>/dev/null
        docker run -d --name eshop-postgres \
            -e POSTGRES_USER=eshop \
            -e POSTGRES_PASSWORD=eshop123 \
            -e POSTGRES_DB=eshop \
            -p 5432:5432 \
            postgres:16
    fi
fi

# Wait until PostgreSQL is ready
echo "⏳ Waiting for PostgreSQL..."
for i in $(seq 1 30); do
    docker exec eshop-postgres pg_isready -q 2>/dev/null && break
    sleep 1
done

# Check if PostgreSQL is actually ready
if ! docker exec eshop-postgres pg_isready -q 2>/dev/null; then
    echo "❌ PostgreSQL failed to start. Aborting."
    exit 1
fi
echo "✅ PostgreSQL is ready"

# ── 2. Always rebuild ───────────────────────────────────────────────────────
echo "🔨 Building project..."
mvn clean package -DskipTests -q

# ── 3. Kill any previous instance on port 8081 ──────────────────────────────
PID=$(lsof -ti:8081 2>/dev/null) || true
if [ -n "$PID" ]; then
    echo "🛑 Stopping previous instance (PID $PID)..."
    kill $PID 2>/dev/null
    sleep 2
fi

# ── 4. Launch ───────────────────────────────────────────────────────────────
echo "🚀 Starting E-Shop..."
nohup java -jar target/eshop-0.0.1-SNAPSHOT.jar > /tmp/eshop.log 2>&1 &
APP_PID=$!
echo "   PID: $APP_PID"

# Wait for ready
echo "⏳ Waiting for application..."
for i in $(seq 1 60); do
    curl -sf http://localhost:8081/ >/dev/null 2>&1 && {
        echo ""
        echo "✅ E-Shop running at http://localhost:8081"
        echo "   API    → http://localhost:8081/api"
        echo "   Front  → http://localhost:8081"
        exit 0
    }
    sleep 1
done

echo "⚠️  App may still be starting. Log: tail -f /tmp/eshop.log"
exit 1
