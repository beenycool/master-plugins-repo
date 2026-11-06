# master-plugins-repo

> Top secret. Signed by mommy pgqn.

Small Paper plugins for the Guab Christmas parkour. Built for Paper `26.3` on Java 25, with one plugin per feature.

## Modules

| Module                   | Purpose                                  |
| ------------------------ | ---------------------------------------- |
| `plugins/guab-core`      | Shared points ledger and helpers         |
| `plugins/guab-blocks`    | Fake blocks, potion blocks, swap blocks  |
| `plugins/guab-items`     | Wind charges, jump boost feathers        |
| `plugins/guab-rickroll`  | The rickroll block                       |
| `plugins/guab-gambling`  | Casino and wagers                        |
| `plugins/guab-minigames` | Battle pit and point minigames           |
| `plugins/guab-webconsole`| Token-protected HTTP command console      |

## Build

```bash
./gradlew build
```

Jars are written to `plugins/<module>/build/libs/`.

## Deploy

Push to `main` and everything else is automatic:

1. GitHub Actions builds every plugin jar into the `deploy-latest` release.
2. It pings the VM's authenticated deploy webhook (`:39001`).
3. The VM announces the restart in-game, swaps the jars and restarts Paper.

If the ping is missed, a 2-minute timer on the VM polls for new releases as a fallback.

**Server details**

- Address: `145.241.198.214:30565`
- Resources: 3 GB heap, 4 GB cap
- World: superflat, 20 slots, no whitelist
- Rickroll admin commands require `guab.rickroll.admin` (OP by default)
- World and plugin configs live in `/opt/guab-server/data` and survive every deploy
- `backup.sh` snapshots the world daily to `/opt/guab-server/backups` (last 7 kept)

**Manual commands on the VM**

```bash
sudo docker/install.sh                    # first-time install
sudo /opt/guab-server/deploy.sh           # no-op if already current
sudo /opt/guab-server/deploy.sh --force   # re-apply the latest release
sudo /opt/guab-server/rollback.sh         # revert; sticks until the next push
```

## Rickroll

`GuabRickroll` marks blocks with `/rickroll setblock`. Stepping on one has a 5% chance of sending the player to a hidden cinema for the video and song. While it plays, the lyrics appear above the hotbar and a pink boss bar counts down the song. Use `/rickroll test` to rickroll yourself.

### Building the video pack

Build a resource pack from your own mp4. Media stays on the VM and is never committed to git.

```bash
sudo tools/build-rickroll-pack.sh /opt/guab-server/media/rickroll.mp4
sudo systemctl restart guab-server-pack.service
sudo docker exec guab-mc rcon-cli rickroll reload
sudo docker exec guab-mc rcon-cli rickroll verify
```

The pack is served at `:39002/rickroll.zip`. Its `pack.properties` passes the frame count, fps, duration and SHA-1 to the plugin.

**Current pack:** a 60-second cut starting at 0:19 (the first lyric), at 12 fps and 192x108, about 7.6 MB.

**How it stays small**

- All frames share one 256-color palette, so colors don't flicker between frames.
- Duplicate frames reuse a single texture.
- PNGs are losslessly recompressed with `oxipng` or `advpng`.
- The source is archived as AV1, but the pack uses PNG frames because Minecraft item textures can't be AV1.

Tune the output with `COLORS` and `DITHER`. `DITHER=none` gives a smaller, sharper result but can show banding on gradients.

## Points

`GuabCore` owns the shared wallet.

- Your balance appears in the sidebar, the tab-list header and under your nametag, refreshed every second.
- `/points` (or `/bal`, `/balance`) shows your balance in chat, and `/points top` shows the leaderboard.
- `/hud` hides or shows your sidebar; the nametag balance stays.
- Joining prints your balance and a clickable `[Enter casino]` button.
- Staff can use `/points set|add|take <player> <amount>`.
- Balances are stored in `plugins/GuabCore/points.yml`, flushed every minute and on shutdown.
- If Vault is installed, GuabCore registers the ledger as the server's Vault economy, so third-party plugins spend Guab points directly.

## Casino event

The casino is a walk-in venue for the one-night event. `GuabGambling` builds it and MineGames powers it.

### Setup

1. Install the pinned dependencies and redeploy:

   ```bash
   sudo /opt/guab-server/install-casino.sh   # pinned VaultUnlocked + MineGames
   sudo /opt/guab-server/deploy.sh --force
   ```

2. In game as an op, build the venue and check the hooks:

   ```
   /casino build     # creates the guab_casino world and paints the station pads
   /casino status    # confirms MineGames, Vault and the points ledger are hooked up
   ```

3. Stand on each pad below and run its create command.
4. Set `event.enabled: true` under `GuabGambling` and run `/casino reload`.

Players then walk in with `/casino`. The venue blocks non-admin building and cancels damage and hunger.

### Station pads

All pads are in the `guab_casino` world at `y=65` (relative to `0, 64, 0`).

| Station  | Pad color | Stand at                                  | Command                  |
| -------- | --------- | ----------------------------------------- | ------------------------ |
| Roulette | Red       | `0 65 0` (centre)                         | `/rouletteadmin create`  |
| Slots    | Lime      | `19 65 -10`, `19 65 -5`, `19 65 0`        | `/slotsadmin create 3 1` |
| Mines    | Blue      | `19 65 7` (on the beacon)                 | `/minegameadmin create`  |
| Chicken  | Yellow    | `-15 65 12`                               | `/chickenadmin create 7` |
| Fights   | Orange    | `-15 65 0` (arena origin)                 | `/fightadmin create 9 2` |
| Dice     | n/a       | Anywhere                                  | `/dice`                  |

### Blackjack

`/blackjack <bet>` (aliases `/bj`, `/21`) deals a hand against the dealer. `/bj hit`, `/bj stand` and `/bj double` play it out; the chat buttons do the same. Blackjack pays 3:2, the dealer stands on 17, and the table settles directly against the points ledger. Hands auto-stand after `blackjack.timeout-seconds` and settle if the player disconnects.

### Chicken

Players bet on red, blue, gold or green, then either cash out at 1.00× for exactly their stake or ride the lightning for a chance at 4.6×. The 7×7 board has 10 black (dead) tiles, so the chance of landing on the chosen colour is **39/196 ≈ 19.9%**, giving a **~91.5% return** for players who never cash out. Cash-out is a break-even escape hatch, not a profit button. Winners are paid even if they disconnect before the round resolves.

### Snapshots

`/casino snapshot` (op) writes `data/backups/casino-snapshot-<timestamp>.zip` containing `points.yml`, the GuabCore/GuabGambling configs and every MineGames YAML.

### Third-party jars

Jars are pinned in `docker/install-casino.sh` with SHA-256 checks and installed to `/opt/guab-server/third-party`. `deploy.sh` reinstalls them after every jar swap.

## Battle pit

`GuabMinigames` runs a free-for-all pit in the overworld at `100, -61, 100` (built into the flat world, 19×19, walls four blocks high).

- `/battlepit join` teleports you in; `/battlepit leave` returns you to spawn.
- Kills pay **50 points**, deaths cost **25**, and the action bar shows the current kill streak.
- Inventory is kept on death and you respawn at the pit spawn.
- `/battlepit setspawn [x y z world]`, `setexit` and `reload` are op-only.

## Web console

`GuabWebConsole` exposes a token-protected HTTP endpoint so a command can be run with `curl`:

```bash
curl "http://HOST:39100/command?token=SECRET&cmd=list"
```

See `plugins/guab-webconsole/README.md` for endpoints, config and the shared-hosting port caveat.

## Adding a plugin

1. Add `"plugins:guab-name"` to `settings.gradle.kts`.
2. Copy an existing module folder, then rename the package, main class and `plugin.yml`.
3. Add `depend: [GuabCore]` to `plugin.yml` if it needs the points ledger.
