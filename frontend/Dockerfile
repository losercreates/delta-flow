# ==============================================================================
# DeltaFlow Operator Dashboard - Multi-Stage Production Dockerfile
# ==============================================================================

# --- Stage 1: Build Frontend Assets ---
FROM node:20-alpine AS builder
WORKDIR /app

# Cache dependency layer
COPY package.json package-lock.json ./
RUN npm ci

# Copy sources and compile production bundle
COPY . .
RUN npm run build

# --- Stage 2: Minimal Nginx Runtime ---
FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

# Clean default nginx files
RUN rm -rf ./*

# Copy compiled SPA bundle and custom Nginx configuration
COPY --from=builder /app/dist .
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
