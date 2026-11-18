# syntax=docker/dockerfile:1.7
# Frontend: build the React app, then serve it with nginx, which also proxies /api to the gateway (plan 3, phase 12).
# Build context is the REPOSITORY ROOT:  docker build -f frontend/Dockerfile -t thermogrid/frontend .
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY infra/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=6 CMD wget -q --spider http://127.0.0.1/ || exit 1
