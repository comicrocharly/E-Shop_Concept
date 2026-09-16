#!/usr/bin/env bash
# =============================================================================
# E-Shop — dati demo (idempotente)
#
#   make seed
#
#   1. immagini articoli (SVG) → hostPath /host/eshop-article-images su TUTTI
#      i node kind (volume condiviso dai 3 pod backend)
#   2. utente admin (admin / $ADMIN_PASSWORD, default admin123) se non esiste
#   3. catalogo: 9 articoli + immagini, solo se il catalogo è vuoto
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

# La password admin può essere iniettata dall'esterno (CI: GitHub secret);
# il default admin123 vale solo per lo sviluppo locale.
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"

NS=eshop
DB=eshop-db-0
API="${API_BASE:-http://localhost:8080}/api"

# psql sul primary: -A (no tuple) -t (solo tuple) -c SQL
psqlq() {
    kubectl -n "$NS" exec "$DB" -- su postgres -c "psql -U eshop -d eshop -q -A -t -c \"$1\""
}

echo "🖼️  1/3 immagini articoli → tutti i node kind..."
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

# slug|nome|categoria|prezzo|stock|colore1|colore2
cat > "$tmp/items.tsv" <<'EOF'
headphones-pro-x|Headphones Pro X|Audio|129.90|25|#1e3a8a|#3b82f6
earbuds-air-lite|Earbuds Air Lite|Audio|79.90|40|#1e40af|#60a5fa
speaker-boom-mini|Speaker Boom Mini|Audio|59.90|30|#312e81|#2563eb
smart-watch-s2|Smart Watch S2|Wearables|199.90|15|#065f46|#34d399
fitness-band|Fitness Band|Wearables|49.90|60|#047857|#6ee7b7
smart-ring|Smart Ring|Wearables|249.90|10|#064e3b|#10b981
usb-c-cable|Cavo USB-C 2m|Accessories|19.90|100|#7c2d12|#fb923c
power-bank-20k|Power Bank 20K|Accessories|39.90|45|#9a3412|#fdba74
charger-pad|Charger Pad Wireless|Accessories|29.90|35|#78350f|#f97316
EOF

while IFS='|' read -r slug name cat price stock c1 c2; do
    cat > "$tmp/$slug.svg" <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="$c1"/>
      <stop offset="100%" stop-color="$c2"/>
    </linearGradient>
  </defs>
  <rect width="800" height="600" fill="url(#g)"/>
  <text x="400" y="290" font-family="Arial, sans-serif" font-size="44" font-weight="bold" fill="#ffffff" text-anchor="middle">$name</text>
  <text x="400" y="350" font-family="Arial, sans-serif" font-size="28" fill="#ffffffcc" text-anchor="middle">$cat</text>
</svg>
SVG
done < "$tmp/items.tsv"

for node in eshop-control-plane eshop-worker eshop-worker2; do
    if docker ps --format '{{.Names}}' | grep -qx "$node"; then
        docker exec "$node" mkdir -p /host/eshop-article-images
        docker cp "$tmp/." "$node":/host/eshop-article-images/
    fi
done
echo "   ✓ $(ls "$tmp"/*.svg | wc -l | tr -d ' ') SVG copiate"

echo "👤 2/3 utente admin..."
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASSWORD\",\"email\":\"admin@eshop.local\"}")
echo "   register → HTTP $code (201 creato / 4xx già esistente)"
psqlq "UPDATE users SET role='ADMIN' WHERE username='admin'" >/dev/null

echo "📦 3/3 catalogo..."
count=$(psqlq "SELECT COUNT(*) FROM articles")
if [ "$count" != "0" ]; then
    echo "   ✓ catalogo già popolato ($count articoli) — salto"
else
    while IFS='|' read -r slug name cat price stock c1 c2; do
        psqlq "WITH a AS (INSERT INTO articles (name, description, category, price, stock, author_id) VALUES ('$name', 'Articolo demo: $name ($cat).', '$cat', $price, $stock, (SELECT id FROM users WHERE username='admin')) RETURNING id) INSERT INTO article_images (article_id, file_name, position) SELECT id, '$slug.svg', 0 FROM a" >/dev/null
    done < "$tmp/items.tsv"
    echo "   ✓ $(psqlq "SELECT COUNT(*) FROM articles") articoli inseriti"
fi

echo "✅ seed completato — admin: admin (password via env)"
