# =============================================================================
# E-Shop frontend — nginx statico
#   - serve index.html + asset statici (niente JVM, ~5 MB)
#   - proxy /api/ → Service backend (stesso origin, zero problemi CORS)
# =============================================================================
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/nginx.conf
COPY index.html favicon.svg favicon.ico /usr/share/nginx/html/
