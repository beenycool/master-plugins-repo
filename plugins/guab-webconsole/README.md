# GuabWebConsole

Runs a Minecraft command from a `curl` request without an RCON client or a game
login. The listener binds to **loopback** (`127.0.0.1`) by default and the
compose file only publishes `127.0.0.1:39100`.

## Endpoints

```
GET  /health                                  -> ok
GET  /command?cmd=list                        + Authorization: Bearer SECRET
POST /command                                 + Authorization: Bearer SECRET
GET  /command?token=SECRET&cmd=list           (legacy; prefer the header)
```

Examples (through an SSH tunnel):

```bash
ssh -N -L 39100:127.0.0.1:39100 user@145.241.198.214
```

In a second terminal:

```bash
curl "http://127.0.0.1:39100/health"
curl -H "Authorization: Bearer SECRET" \
     "http://127.0.0.1:39100/command?cmd=list"
curl -H "Authorization: Bearer SECRET" \
     "http://127.0.0.1:39100/command?cmd=points%20pgqn"
curl -X POST -H "Authorization: Bearer SECRET" \
     --data "say hello from curl" "http://127.0.0.1:39100/command"
```

The POST token must come from the `Authorization`/`X-Guab-Token` header or the
query string. The body is not read until the request is authenticated, so a
token in the body is intentionally not accepted.

The response is the command's output as plain text (`OK` when a command prints
nothing). HTTP status codes: `401` bad token, `403` blocked IP/command, `429`
rate limited, `413` body/command too long, `500` server-side error.

## Config

| Key | Default | Meaning |
| --- | --- | --- |
| `web.enabled` | `true` | Start the listener. |
| `web.bind` | `127.0.0.1` | Interface to bind. Keep loopback. |
| `web.port` | `39100` | TCP port. |
| `web.token` | placeholder | Shared secret. The plugin refuses to start with the placeholder. |
| `web.request-timeout-seconds` | `10` | How long to wait for the main thread. |
| `web.max-command-length` | `1000` | Reject longer commands. |
| `web.max-body-bytes` | `16384` | Reject larger POST bodies before reading them. |
| `web.rate-limit-per-minute` | `60` | Per-source-IP cap, including failed auth attempts. |
| `web.allowed-ips` | `[]` | Empty = any IP, otherwise an allowlist. |
| `web.blocked-prefixes` | `[]` | Commands starting with these are refused. |

Generate a token with `openssl rand -hex 32` and put it in `config.yml`.

## Docker deployment

`docker/docker-compose.yml` publishes only the loopback address:

```yaml
ports:
  - "127.0.0.1:39100:39100"
```

That means the port is reachable from the VM itself, but not from the
internet. Connect with the SSH tunnel shown above.

Docker forwards a published port to the container interface, not to the
container's loopback. Because the host side is still bound to `127.0.0.1`, it is
safe (and necessary) to set `web.bind: 0.0.0.0` in the **container's**
`config.yml`; external clients still cannot reach it except through the SSH
tunnel. Bare-metal installs should keep the default `127.0.0.1`.

## Security notes

- The token is the only authentication. Treat it like a password.
- Header tokens are preferred; query tokens can leak through access logs,
  browser history and shell history. `/command` responses are `no-store`.
- The POST body is only read after the IP check, rate limit and token check.
- Prefer `allowed-ips` when you have a fixed address.
- Keep `blocked-prefixes` populated with destructive commands (`op`, `deop`,
  `stop`, `restart`) unless you specifically need them.
- Requests are logged to the server log with the source IP and command.
- Run over HTTPS only if you put a reverse proxy in front; the plugin speaks
  plain HTTP.
