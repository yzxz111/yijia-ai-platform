FROM node:22.17.0-alpine@sha256:fc3e945f920b7e3000cd1af86c4ae406ec70c72f328b667baf0f3a8910d69eed AS certificates

FROM node:22.17.0-bookworm-slim@sha256:b04ce4ae4e95b522112c2e5c52f781471a5cbc3b594527bcddedee9bc48c03a0 AS runtime-base

COPY --from=certificates /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt

RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' \
        /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        fonts-dejavu-core \
        libreoffice-calc \
        libreoffice-writer \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 converter \
    && useradd --uid 10001 --gid converter --no-create-home --shell /usr/sbin/nologin converter

WORKDIR /app
COPY --chown=converter:converter package.json ./
COPY --chown=converter:converter src ./src
COPY --chown=converter:converter registrymodifications.xcu ./registrymodifications.xcu

ENV NODE_ENV=production \
    PORT=8080 \
    SOFFICE_PATH=/usr/bin/soffice \
    DOCUMENT_CONVERTER_TEMP_ROOT=/tmp/document-converter \
    DOCUMENT_CONVERTER_REGISTRY_TEMPLATE=/app/registrymodifications.xcu

FROM runtime-base AS verification
COPY --chown=converter:converter test ./test
USER converter
RUN node --test test/*.test.mjs \
    && touch /tmp/document-converter-build-verified

FROM runtime-base AS runtime
COPY --from=verification --chown=converter:converter \
    /tmp/document-converter-build-verified /app/.build-verified
USER converter
CMD ["node", "src/server.mjs"]
