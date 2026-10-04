# Hosted hub. Build context is this directory; drop the release tarball next to it (see HOSTING.md).
FROM node:22-alpine
WORKDIR /app
ARG TARBALL=mobilemcp-0.1.0.tgz
COPY ${TARBALL} /tmp/mobilemcp.tgz
RUN npm install -g /tmp/mobilemcp.tgz && rm /tmp/mobilemcp.tgz
ENV MOBILEMCP_HOST=0.0.0.0 MOBILEMCP_PORT=17692 MOBILEMCP_DISABLE_IPC=1
EXPOSE 17692
USER node
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:17692/healthz || exit 1
CMD ["mobilemcp-hub"]
