# syntax=docker/dockerfile:1
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_API_MODE=real
ARG VITE_ENABLE_KB_MEMBERSHIP=false
ARG VITE_ENABLE_DIAGNOSTICS=false
ENV VITE_API_MODE=$VITE_API_MODE \
    VITE_ENABLE_KB_MEMBERSHIP=$VITE_ENABLE_KB_MEMBERSHIP \
    VITE_ENABLE_DIAGNOSTICS=$VITE_ENABLE_DIAGNOSTICS
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
