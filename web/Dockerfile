# --- Build stage ---
FROM node:22-alpine AS builder

WORKDIR /src

# Copy manifest + patches first so `npm ci` (which runs the `postinstall`
# patch-package step -- see package.json) is cached independently of source
# changes, mirroring the layer-caching intent of ../backend/Dockerfile.
COPY package.json package-lock.json ./
COPY patches ./patches
RUN npm ci

COPY . .

# Baked in at build time -- Vite inlines env vars starting with VITE_ into
# the static bundle, there is no runtime env injection for a static nginx
# serve (see web/README.md / CLAUDE.md Sprint 8 notes). Defaults to the live
# public backend since that's what every real deployment of this image
# should call, even though the Web app itself is LAN-only.
ARG VITE_API_BASE_URL=https://veilkeeper.quezacolt.my.id/
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN npm run build

# --- Runtime stage ---
# nginx-unprivileged already runs as a non-root user (uid 101, "nginx") and
# listens on a non-privileged port by default -- simpler and no less secure
# than hand-rolling non-root nginx permissions, keeps this image minimal
# per the "Secure/Simple/Fast/Lightweight/Maintainable" principle.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

COPY --from=builder /src/dist /usr/share/nginx/html
COPY web.nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
