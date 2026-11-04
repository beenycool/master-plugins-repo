# master-plugins-repo

Top secret. Signed by mommy pgqn.

Small Paper plugins for the Guab Christmas parkour. Paper `26.3`, Java 25, one plugin per feature.

| Module                    | Purpose                                  |
| ------------------------- | ---------------------------------------- |
| `plugins/guab-core`      | Shared points ledger and helpers.        |
| `plugins/guab-blocks`    | Fake blocks, potion blocks, swap blocks. |
| `plugins/guab-items`     | Wind charges, jump boost feathers.       |
| `plugins/guab-rickroll`  | The 1% rickroll block.                   |
| `plugins/guab-gambling`  | Casino and wagers.                       |
| `plugins/guab-minigames` | Battle pit and point minigames.          |

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
- Rickroll admin commands use `guab.rickroll.admin` (OP by default).
- World and plugin configs live in `/opt/guab-server/data` and survive every deploy.
- `backup.sh` snapshots the world daily at `/opt/guab-server/backups` (last 7 kept).

```bash
sudo docker/install.sh                    # first-time install
sudo /opt/guab-server/deploy.sh          # no-op if current
sudo /opt/guab-server/deploy.sh --force  # re-apply latest
sudo /opt/guab-server/rollback.sh        # revert, sticks until the next push
```

## Rickroll

`GuabRickroll` tags blocks with `/rickroll setblock`; stepping on one has a 1% chance to send
the player to a hidden cinema for the video and song. `/rickroll test` rickrolls yourself.

Build a pack from your own mp4 (media stays on the VM, never in git):

```bash
sudo tools/build-rickroll-pack.sh /opt/guab-server/media/rickroll.mp4
sudo systemctl restart guab-server-pack.service
sudo docker exec guab-mc rcon-cli rickroll reload
sudo docker exec guab-mc rcon-cli rickroll verify
```

The pack is served at `:39002/rickroll.zip`. `pack.properties` feeds frame count, fps, duration
and SHA-1 to the plugin. The current pack is the full 3:33 at 10 fps and 192x108 (~24 MB), quantized to a 128-color
palette. The source is archived as AV1, but the pack itself is PNG frames because Minecraft
item textures cannot be AV1. When `pngquant` is installed the build quantizes each frame;
without it the same pack is roughly 60 MB.

## Add a plugin

1. Add `"plugins:guab-name"` to `settings.gradle.kts`.
2. Copy a module folder, rename the package, main class and `plugin.yml`.
3. Add `depend: [GuabCore]` if it needs the points ledger.
