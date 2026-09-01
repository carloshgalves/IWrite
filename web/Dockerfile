FROM node:26-alpine@sha256:2d984a15c9b54fd0aeb608b8e0d0d83529eb34d2966db27a1fb4f1edc3d298a3 AS deps

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

FROM node:26-alpine@sha256:2d984a15c9b54fd0aeb608b8e0d0d83529eb34d2966db27a1fb4f1edc3d298a3 AS build

WORKDIR /app

# next build serialises the rewrites into .next/routes-manifest.json, and `next start` reads that
# manifest rather than re-running next.config.ts. So the backend origin has to be known here at
# build time - supplying it only at runtime leaves the image pinned to the local fallback.
#
# Neither ARG has a default: an unset build-arg becomes an empty ENV value, and next.config.ts's
# resolveBackendOrigin() already treats an empty string as absent, falling through to
# NEXT_PUBLIC_API_URL and then to its own local fallback. Giving BACKEND_ORIGIN a non-empty default
# here would short-circuit that fallback chain before next.config.ts ever saw it, permanently
# breaking existing deployments that only ever set the legacy NEXT_PUBLIC_API_URL.
ARG BACKEND_ORIGIN
ARG NEXT_PUBLIC_API_URL
ENV BACKEND_ORIGIN=$BACKEND_ORIGIN
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL

COPY --from=deps /app/node_modules ./node_modules
COPY . .

RUN npm run build

FROM node:26-alpine@sha256:2d984a15c9b54fd0aeb608b8e0d0d83529eb34d2966db27a1fb4f1edc3d298a3 AS runner

WORKDIR /app

ENV NODE_ENV=production
# BACKEND_ORIGIN is read by next.config.ts at runtime and supplied by compose: the browser only
# ever calls /api on this origin, so no backend URL is baked into the bundle.

COPY --from=build /app/package.json ./package.json
COPY --from=build /app/next.config.ts ./next.config.ts
COPY --from=build /app/server.mjs ./server.mjs
COPY --from=build /app/public ./public
COPY --from=build /app/.next ./.next
COPY --from=build /app/node_modules ./node_modules

EXPOSE 3000

CMD ["npm", "run", "start"]
