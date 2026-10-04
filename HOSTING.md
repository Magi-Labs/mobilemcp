# Hosting the hub

The hub is stateless: one small Node process that the phone dials into and agents query. Any box with Docker and a TLS-terminating reverse proxy works.

## Docker

```sh
mkdir mobilemcp && cd mobilemcp
curl -LO https://github.com/Magi-Labs/mobilemcp/releases/download/v0.1.0/mobilemcp-0.1.0.tgz   # or copy it from a local npm pack
cp deploy/Dockerfile deploy/compose.yaml .
cp deploy/.env.example .env && $EDITOR .env            # public URL + a 32+ character token
docker compose up -d --build
curl -s http://127.0.0.1:17692/healthz                  # {"status":"ok"}
```

Environment:

| Variable | Purpose |
| --- | --- |
| `MOBILEMCP_PUBLIC_URL` | HTTPS origin the proxy serves, e.g. `https://mobilemcp.example.com`. Required with a token; `Host` must match. |
| `MOBILEMCP_TOKEN` | Shared token: the phone sends it in `hello.token`, agents as `Authorization: Bearer`. |
| `MOBILEMCP_ACCOUNTS` | Instead of one token: `[{"id":"me","agentToken":"…","deviceToken":"…"}]`. Devices and selections never cross accounts. |
| `MOBILEMCP_HOST` / `MOBILEMCP_PORT` | Bind address/port; the image sets `0.0.0.0:17692`. |
| `MOBILEMCP_DISABLE_IPC` | `1` in containers (no local stdio clients). |

## Reverse proxy

Terminate TLS and forward both HTTP and WebSocket upgrades to the hub. Traefik file-provider example:

```yaml
http:
  routers:
    mobilemcp:
      rule: Host(`mobilemcp.example.com`)
      entryPoints: [websecure]
      service: mobilemcp
      tls: { certResolver: letsencrypt }
  services:
    mobilemcp:
      loadBalancer:
        servers: [{ url: "http://10.10.0.2:17692" }]
```

Caddy: `mobilemcp.example.com { reverse_proxy 10.10.0.2:17692 }`.

## Dashboard

The hub serves a dashboard at `/` (devices, live activity, per-device live view with click-to-tap). On an authenticated hub it asks for the agent token and keeps it in the browser's local storage. The API behind it: `GET /api/status`, `GET /api/events` (server-sent events; `?token=` accepted because EventSource cannot set headers) and `POST /api/call {deviceId?, action, params}`. Activity events never contain screen content.

## Connect

- Phone app: hub URL `wss://mobilemcp.example.com`, device token = `MOBILEMCP_TOKEN`.
- Agent over HTTP: `{"type":"http","url":"https://mobilemcp.example.com/mcp","headers":{"Authorization":"Bearer <token>"}}`.
- Agent over stdio with a local hub stays unchanged; a stdio process cannot reach a remote hub (it uses the IPC socket).

Put the release APK next to `compose.yaml` as `mobilemcp.apk` and the hub serves it at `GET /app.apk?token=<device token>` (`MOBILEMCP_APK`), so a phone can download its own update from the hub.

`GET /healthz` is unauthenticated and reports the local device count only for unauthenticated hubs.
