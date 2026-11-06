# master-plugins-repo

> Top secret. Signed by mommy pgqn.

Small Paper plugins for the Guab Christmas parkour. Built for Paper `26.3` on Java 25, with one plugin per feature.

## Modules

| Module                   | Purpose                                  |
| ------------------------ | ---------------------------------------- |
| `plugins/guab-core`      | Shared points ledger and helpers         |
| `plugins/guab-blocks`    | Fake blocks, potion blocks, swap blocks  |
| `plugins/guab-items`     | Wind charges, jump boost, beer and darts |
| `plugins/guab-rickroll`  | The rickroll block                       |
| `plugins/guab-gambling`  | Casino and wagers                        |
| `plugins/guab-minigames` | Battle pit and point minigames           |
| `plugins/guab-webconsole`| Token-protected HTTP command console      |

## Build

```bash
./gradlew build
```

Jars are written to `plugins/<module>/build/libs/`. Darts scoring and checkout
logic have unit tests:

```bash
./gradlew :plugins:guab-items:test
```

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
- Command blocks are disabled (`enable-command-block=false` in `data/server.properties`)
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

A dedicated **Blackjack Dealer** NPC stands in the main casino hall near the bar at `-5.5, 65, -13`, outside the pub. Right-click the dealer to open a 26.3 dialog, type a bet and press **Deal**. Right-click again during a hand to reprint your cards and the Hit / Stand / Double buttons. Staff can move the dealer to their current position with `/blackjack setdealer`.

`/blackjack <bet>` (aliases `/bj`, `/21`) also deals a hand directly. `/bj hit`, `/bj stand` and `/bj double` play it out; the chat buttons do the same. Blackjack pays 3:2, the dealer stands on 17, and the table settles directly against the points ledger. Hands auto-stand after `blackjack.timeout-seconds` and settle if the player disconnects.

### Chicken

Players bet on red, blue, gold or green, then either cash out at the live multiplier or ride the lightning for the 4.6× colour bonus. The 7×7 board has 10 black (dead) tiles, so the chance of landing on the chosen colour is **39/196 ≈ 19.9%**. The chicken's multiplier climbs as it walks and golden pickups add to it, so cashing out early is a real choice rather than a flat refund. Winners are paid even if they disconnect before the round resolves.

If the chicken sticks at `1.00×`, run `sudo docker/fix-minegames-chicken.sh` on the VM. It restores `multiplier-increase-per-second: 0.03` and `pickup-multiplier: 0.25`, then reloads MineGames.

### Wetherspoons

A small Wetherspoons pub sits in the north-west corner of the casino hall (`x -24..-16`, `z -16..-10`). Right-click **any barrel in the pub** to open the 26.3 beer menu and choose exactly what you want; the menu buttons buy the pint directly. Shift-right-click a barrel for a quick random **1-point house pint**, or run `/beer` / right-click a menu shelf to open the same picker.

The menu has **14 branded pints** across five styles: Lager, Stout, Ale, Cider and Alcohol-Free. Every pint carries its own real brand name and matching bottle/can picture in the inventory — Stella, Guinness, Carling, Peroni, Strongbow and the rest — instead of all saying just “Beer”. Each style still has its own ABV, price, flavour lines and potion effects. Beer stacks to **64 per brand**. Drinking is a real 26.3 `CONSUMABLE` component, and left/right-click handling is instant so a quick click always drinks. Old tagged pints are migrated to their branded item the first time they are used or when the player logs in.

Staff can hand out stacks with `/guabitems give <player> beer 64`, a specific brand with `/guabitems give <player> beer:stella 64`, or a style with `/guabitems give <player> beer:stout 64` (styles pick a random brand).

The item textures live in `guab-items.zip` on the pack host (`:39002`) and are applied as the server resource pack; the same assets are embedded in the rickroll pack so beer stays textured after a show. The pint and bottle textures are real product photos from Open Food Facts (CC BY-SA 3.0), and the dartboard face is Wikimedia Commons' unlabelled dartboard (CC BY-SA 3.0).

Rebuild both packs with:

```bash
sudo tools/build-guab-items-pack.py
```

It writes `/tmp/guab-pack/guab-items.zip` and `/tmp/guab-pack/rickroll.zip` and prints their SHA-1 values. Copy them into `/opt/guab-server/resourcepack/`, update `resource-pack-sha1` in `data/server.properties` and `sha1` in `data/plugins/GuabRickroll/pack.properties`, then restart Paper.

**Pub features**

- **Weighted drunk meter** — every pint contributes real alcohol units based on ABV and volume. They decay over the configured window (15 minutes by default); the action bar shows `Tipsy → Merry → Drunk → Wrecked → Absolutely Wetherspoons`, with escalating nausea, slowness, blindness and the occasional hangover. Effects are replaced as the meter drops, so they weaken and disappear instead of lingering at the old tier. Milk clears the meter immediately.
- **Pints leaderboard** — lifetime pints and units are saved to `pints.yml`, `/pints [top|units]` lists them, and a hologram above the bar shows the top three plus total pints served.
- **Milestones** — the 1st, 10th, 25th, 50th and 100th lifetime pint each pay a one-off points reward.
- **`/round [beer]`** — buys that brand (or a random brand from a style) for everyone within 14 blocks of the pub centre; the default house round stays 1 point each.
- **`/beer`** — opens the dialog menu; `/beer list`, `/beer buy <beer> [amount]`, `/beer milk` and `/beer stats` also work.
- **Milk** — sold from the bar menu for 5 points. Drinking it clears the drunk meter and the nausea/slowness effects, so you can sober up without waiting.
- **Pub quiz** — every 10 minutes (or `/quiz`) a question goes out in chat; the first correct answer wins 100 points.
- **Beer menu shelves** — right-click a shelf to open the dialog menu; left-click still refuses to let anyone take the display bottles.
- **Carpet** — the pack overrides `red_carpet` with the Wetherspoons-style pattern, so the pub rug and the casino aisles share it.

### Darts

The pub target block is the **entrance** to a dedicated darts room. Right-click it, pick **101, 301 or 501**, and you are teleported to a private oche with a bow and 64 arrows. Shoot the target block on the far wall; the arrow impact is scored on the real board geometry.

**How to play:** right-click the pub dartboard, pick a leg, then hold right-click to draw the bow and release to shoot the target. You are locked to your oche, so you cannot walk closer or further away. Shift-right-click the pub board for a fast default 501 leg. Three arrows per visit; count down to exactly zero and finish on a double or bullseye 50. `/darts quit` returns you to the pub; `/darts help` prints the instructions, and the checkout hint appears on the action bar. Up to four players can share the room.

- **Real arrows.** The arrow sticks in the board for a moment, and chat tells you exactly what you hit (`T20`, `D16`, `Bullseye 50`, `Miss`). The 3×3 board uses the twenty standard sectors, plus singles, doubles, trebles, outer bull (25) and bullseye (50). Hitting T20 shows a **TRIPLE 20 / 60** title in the middle of the screen.
- **Dedicated room.** The `guab_darts` world has a lit oche, a 3×3 target, a bow, arrows, and no mobs or PvP. Movement is locked while the leg is live. You are returned automatically after the match or with `/darts quit`.
- **Shared hit hologram.** A hologram between the oches shows every active player's current score and last hit, so you and a friend can see each other's darts.
- **Proper legs.** Each visit is three arrows, the score counts down, and the last arrow must be a double (bullseye 50 counts). Bust overshoots, leaves 1, or finishes on a non-double.
- **Checkout hints.** The action bar and the room display show the shortest finish, e.g. `T20 T20 BULL` for 170.
- **Drunk wobble.** Every pint in the last window adds Gaussian aim error, so the target genuinely gets harder as the night goes on.
- **Career records.** `darts.yml` stores legs played/won, darts thrown, best leg, 180s, checkouts, highest checkout and best visit. `/darts stats [player]` and `/darts top [legs|180s|checkout|best]` read it.
- **Match night.** `/darts start [101|301|501] [legs]` starts directly. Checkouts pay points based on the mode, darts used and checkout value, capped by config. 180s get fireworks and a title; checkouts get a celebration and a pub-wide announcement.
- **Admin.** `/darts board` sets the pub entrance from the block you are looking at; `/darts reload` reloads the rules.

The pub entrance, face, ring sizes, wobble, rewards and cooldowns are all in `pub.darts` in `GuabItems/config.yml`.

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
