# GuabWebConsole

Runs a Minecraft command from a `curl` request. Intended for hosts that expose an
extra TCP port, so an operator can run console commands without an RCON client or
a game login.

## Endpoints

```
GET  /health                                  -> ok
GET  /command?token=SECRET&cmd=list
POST /command?token=SECRET  (body = command)
GET  /command  with  Authorization: Bearer SECRET
```

Examples:

```bash
curl "http://145.241.198.214:39100/health"
curl "http://145.241.198.214:39100/command?token=SECRET&cmd=list"
curl "http://145.241.198.214:39100/command?token=SECRET&cmd=points%20pgqn"
curl -H "Authorization: Bearer SECRET" \
     "http://145.241.198.214:39100/command?cmd=points%20top%205"
curl -X POST -H "Authorization: Bearer SECRET" \
     --data "say hello from curl" "http://145.241.198.214:39100/command"
```

The response is the command's output as plain text (`OK` when a command prints
nothing). HTTP status codes: `401` bad token, `403` blocked IP/command, `429`
rate limited, `413` command too long, `500` server-side error.

## Config

| Key | Default | Meaning |
| --- | --- | --- |
| `web.enabled` | `true` | Start the listener. |
| `web.bind` | `0.0.0.0` | Interface to bind. |
| `web.port` | `39100` | TCP port. |
| `web.token` | placeholder | Shared secret. The plugin refuses to start with the placeholder. |
| `web.request-timeout-seconds` | `10` | How long to wait for the main thread. |
| `web.max-command-length` | `1000` | Reject longer commands. |
| `web.rate-limit-per-minute` | `60` | Per-source-IP request cap. |
| `web.allowed-ips` | `[]` | Empty = any IP, otherwise an allowlist. |
| `web.blocked-prefixes` | `[]` | Commands starting with these are refused. |

Generate a token with `openssl rand -hex 32` and put it in `config.yml`.

## PebbleHost / shared hosting caveat

A shared Minecraft plan normally exposes **only the game port**. A plugin cannot
make a second port reachable on its own: the host has to route it to the server
container. Before relying on this plugin:

1. Ask PebbleHost whether your plan supports an extra TCP port and which one.
2. Set `web.port` to that port and open it in the panel if the panel allows it.
3. Test with `curl http://HOST:PORT/health` from outside.

If extra ports are not available, the alternative is a **relay**: the plugin
polls an outbound HTTPS URL that you control and executes queued commands. That
works through any NAT because the server only makes outbound requests. Ask for
`relay mode` if you need it.

## Security notes

- The token is the only authentication. Treat it like a password.
- Prefer `allowed-ips` when you have a fixed address.
- Keep `blocked-prefixes` populated with destructive commands (`op`, `deop`,
  `stop`, `restart`) unless you specifically need them.
- Requests are logged to the server log with the source IP and command.
- Run over HTTPS only if you put a reverse proxy in front; the plugin speaks
  plain HTTP.
