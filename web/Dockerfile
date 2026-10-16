FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS deps

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build

WORKDIR /app

# next build serialises the rewrites into .next/routes-manifest.json, and `next start` reads that
# manifest rather than re-running next.config.mjs. So the backend origin has to be known here at
# build time - supplying it only at runtime leaves the image pinned to the local fallback.
#
# Neither ARG has a default: an unset build-arg becomes an empty ENV value, and next.config.mjs's
# resolveBackendOrigin() already treats an empty string as absent, falling through to
# NEXT_PUBLIC_API_URL and then to its own local fallback. Giving BACKEND_ORIGIN a non-empty default
# here would short-circuit that fallback chain before next.config.mjs ever saw it, permanently
# breaking existing deployments that only ever set the legacy NEXT_PUBLIC_API_URL.
ARG BACKEND_ORIGIN
ARG NEXT_PUBLIC_API_URL
ENV BACKEND_ORIGIN=$BACKEND_ORIGIN
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL

COPY --from=deps /app/node_modules ./node_modules
COPY . .

RUN npm run build

# Keep Vitest/Playwright and other build-only tools out of the production image.
# The runtime receives this pruned node_modules tree, not the build dependencies.
RUN npm prune --omit=dev --ignore-scripts

FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS runner

WORKDIR /app

ENV NODE_ENV=production
# Backend origin is embedded in the manifest at build time; the browser only
# ever calls /api on this origin, so no backend URL is baked into the bundle.

COPY --from=build /app/package.json ./package.json
COPY --from=build /app/next.config.mjs ./next.config.mjs
COPY --from=build /app/server.mjs ./server.mjs
COPY --from=build /app/public ./public
COPY --from=build /app/.next ./.next
COPY --from=build /app/node_modules ./node_modules

EXPOSE 3000

CMD ["npm", "run", "start"]
