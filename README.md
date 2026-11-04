# master-plugins-repo

Top secret. Signed by mommy pgqn.

Small Paper plugins for the Guab Christmas parkour. Paper `26.3`, Java 25, one plugin per feature.

| Module                    | Purpose                                  |
| ------------------------- | ---------------------------------------- |
| `plugins/troll-core`      | Shared points ledger, helpers, auto-OP.  |
| `plugins/troll-blocks`    | Fake blocks, potion blocks, swap blocks. |
| `plugins/troll-items`     | Wind charges, jump boost feathers.       |
| `plugins/troll-rickroll`  | The 1% rickroll block.                   |
| `plugins/troll-gambling`  | Casino and wagers.                       |
| `plugins/troll-minigames` | Battle pit and point minigames.          |

## Build

```bash
./gradlew build
```

Jars land in `plugins/<module>/build/libs/`.

## Deploy

Push to `main`. GitHub Actions builds all six jars into the `deploy-latest` release, then pings
the VM's authenticated deploy webhook (`:39001`). The VM announces the restart in-game, swaps
jars and restarts Paper. A 2-minute timer still polls as a fallback if the ping is missed.

- Server: `145.241.198.214:30565` — 3 GB heap, 4 GB cap, superflat, 20 slots, no whitelist.
- Everyone who joins is opped automatically.
- World and plugin configs live in `/opt/troll-server/data` and survive every deploy.
- `backup.sh` snapshots the world daily at `/opt/troll-server/backups` (last 7 kept).

```bash
sudo docker/install.sh                    # first-time install
sudo /opt/troll-server/deploy.sh          # no-op if current
sudo /opt/troll-server/deploy.sh --force  # re-apply latest
sudo /opt/troll-server/rollback.sh        # revert, sticks until the next push
```

## Rickroll

`TrollRickroll` tags blocks with `/rickroll setblock`; stepping on one has a 1% chance to send
the player to a hidden cinema for the video and song. `/rickroll test` rickrolls yourself.

Build a pack from your own mp4 (media stays on the VM, never in git):

```bash
sudo tools/build-rickroll-pack.sh /opt/troll-server/media/rickroll.mp4
sudo systemctl restart troll-server-pack.service
sudo docker exec troll-mc rcon-cli rickroll reload
sudo docker exec troll-mc rcon-cli rickroll verify
```

The pack is served at `:39002/rickroll.zip`. `pack.properties` feeds frame count, fps, duration
and SHA-1 to the plugin.

## Add a plugin

1. Add `"plugins:troll-name"` to `settings.gradle.kts`.
2. Copy a module folder, rename the package, main class and `plugin.yml`.
3. Add `depend: [TrollCore]` if it needs the points ledger.
