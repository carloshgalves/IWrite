FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS backend-build

WORKDIR /backend

COPY pom.xml .
COPY .mvn .mvn
COPY src src

RUN mvn -s .mvn/local-settings.xml -DskipTests package


FROM node:26-alpine@sha256:2d984a15c9b54fd0aeb608b8e0d0d83529eb34d2966db27a1fb4f1edc3d298a3 AS frontend-deps

WORKDIR /frontend

COPY web/package.json web/package-lock.json ./

RUN npm ci


FROM node:26-alpine@sha256:2d984a15c9b54fd0aeb608b8e0d0d83529eb34d2966db27a1fb4f1edc3d298a3 AS frontend-build

WORKDIR /frontend


# next build bakes the rewrite destination into .next/routes-manifest.json, so it has to be known
# here at build time. This image runs frontend and backend in the same container, backend on
# 127.0.0.1:8085 — the same value next.config.ts would fall back to anyway; set explicitly so the
# intent does not depend on that default.
ENV BACKEND_ORIGIN="http://127.0.0.1:8085"

COPY --from=frontend-deps /frontend/node_modules ./node_modules
COPY web/ .

RUN npm run build


FROM node:26-alpine@sha256:2d984a15c9b54fd0aeb608b8e0d0d83529eb34d2966db27a1fb4f1edc3d298a3 AS runtime

RUN apk add --no-cache openjdk21-jre

WORKDIR /app

ENV NODE_ENV=production
# Esta imagem serve o frontend na própria porta 8080, então o navegador manda
# Origin: http://localhost:8080 (ou 127.0.0.1:8080). O default global do
# WebConfig (localhost:3000) é para o Compose separado, onde o frontend roda
# em outra origem — aqui ele rejeitaria login/mutations com 403 Invalid CORS.
# `ENV` só define um default: um `-e APP_CORS_ALLOWED_ORIGINS=...` em runtime
# ainda vence. Um domínio real precisa ser informado no deployment de produção.
ENV APP_CORS_ALLOWED_ORIGINS="http://localhost:8080,http://127.0.0.1:8080"

COPY --from=backend-build \
    /backend/target/iwrite-backend-0.0.1-SNAPSHOT.jar \
    /app/backend/app.jar

COPY --from=frontend-build /frontend/package.json /app/frontend/package.json
COPY --from=frontend-build /frontend/next.config.ts /app/frontend/next.config.ts
# The same custom server web/Dockerfile runs, not `next start`: it strips the forwarding headers a
# client sends and derives X-Forwarded-For from the socket peer. Without it every login inside this
# image would reach the backend as 127.0.0.1 and share a single rate-limit bucket. It resolves the
# Next app directory from its own location, so /app/frontend works here unchanged.
COPY --from=frontend-build /frontend/server.mjs /app/frontend/server.mjs
COPY --from=frontend-build /frontend/public /app/frontend/public
COPY --from=frontend-build /frontend/.next /app/frontend/.next
COPY --from=frontend-build /frontend/node_modules /app/frontend/node_modules

# OpenTelemetry Java Agent 2.30.0, versão fixa com SHA-256 validado pelo BuildKit.
ADD --checksum=sha256:9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d \
    --chmod=444 \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar \
    /app/otel/opentelemetry-javaagent.jar

COPY --chmod=555 docker/start.sh /app/start.sh

EXPOSE 8080

# Exercise the frontend proxy, backend and PostgreSQL in one probe. /api/ping is rewritten by Next
# to the backend's database-aware /ping endpoint; any frontend, backend or database failure makes
# the container unhealthy instead of accepting the frontend-only /health response.
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
  CMD node -e "fetch('http://127.0.0.1:8080/api/ping',{redirect:'manual'}).then(r => process.exit(r.status === 200 ? 0 : 1)).catch(() => process.exit(1))"
CMD ["/app/start.sh"]