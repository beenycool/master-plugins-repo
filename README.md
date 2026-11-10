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

Jars are written to `plugins/<module>/build/libs/`. Darts scoring/slots, blackjack
maths, drunk-beer and quiz rules, and the points/HUD/preferences helpers have
unit tests; none of them needs a live server:

```bash
./gradlew test
```

Run the repository checks (Gradle build/tests, shell/Python syntax, YAML, compose,
jar and `plugin.yml` versions, and the optional rickroll pack) with:

```bash
tools/verify.sh
SKIP_BUILD=1 tools/verify.sh   # reuse the existing build
```

`tools/check-docs.py` is a separate, non-fatal docs check: it parses every
`plugin.yml` and reports command names, aliases and permissions that are not
mentioned in this README. It is intentionally not part of `tools/verify.sh`.

## Deploy

Push to `main` and everything else is automatic:

1. GitHub Actions builds every plugin jar into the `deploy-latest` release.
2. It pings the VM's authenticated deploy webhook (`:39001`).
3. The VM announces the restart in-game, swaps the jars and restarts Paper.

If the ping is missed, a 2-minute timer on the VM polls for new releases as a fallback.

The deploy is health-gated. `deploy.sh` stops Paper with a 90-second timeout
(`docker compose down --timeout 90`, matching `stop_grace_period: 120s`), swaps
the jars, then runs `docker compose up -d --wait --wait-timeout 420`. It only
writes `.deployed-build` after the container healthcheck passes. If the new jars
fail, it restores the jars in `previous/` and boots them instead, so a broken
build does not leave the server down. `rollback.sh` does the same swap manually
and stays on the old build until a new one is published.

Create `/opt/guab-server/.deploy-paused` to stop both the webhook and the timer
from deploying (write a reason inside the file). Deploy and rollback exit
immediately while it exists; the webhook returns `503`. Remove it to resume.
Before maintenance or a restart that is not a deploy, run `/guabcore save`
(alias `/gcore`) in game or from the console to flush points, stats and
preferences; `/guabcore status` shows the ledger, save timer, HUD and Vault state.

**CI and path filters**

`.github/workflows/ci.yml` runs on every pull request, every push to `main` and
manual dispatch. It builds and tests the whole suite, then runs
`SKIP_BUILD=1 bash tools/verify.sh`. The deploy workflow only runs when plugin
code, Gradle files or the wrapper change (`plugins/**`, `build.gradle.kts`,
`settings.gradle.kts`, `gradle/**`, `gradlew`, `gradlew.bat`), so a docs, docker
or tools-only push is verified without restarting the live server.

**Server details**

- Address: `145.241.198.214:30565`
- Resources: 3 GB heap, 4 GB cap
- World: superflat, 20 slots, no whitelist
- Rickroll admin commands require `guab.rickroll.admin` (OP by default)
- Command blocks are disabled (`enable-command-block=false` in `data/server.properties`)
- World and plugin configs live in `/opt/guab-server/data` and survive every deploy
- `backup.sh` runs daily to `/opt/guab-server/backups` (last 7 kept). It includes
  all three worlds, **`data/plugins`** (points, stats, preferences, darts/pints,
  saved inventories and plugin configs) and `data/server.properties`. It uses
  RCON `save-off`/`save-all flush` around the archive so the tarball is consistent.

**Restore from a backup**

The guarded helper stages and verifies the archive first, stops the server,
keeps the current `data/` as `data.pre-restore-<timestamp>`, overlays the
backup, and starts Paper again. It rolls the previous data back if anything
fails. Use `--dry-run` to inspect the archive without touching the server:

```bash
cd /opt/guab-server
sudo docker/restore.sh backups/world-YYYYMMDD-HHMMSS.tar.gz --dry-run
sudo docker/restore.sh backups/world-YYYYMMDD-HHMMSS.tar.gz
```

To restore by hand instead, stop Paper first so nothing writes while files are
replaced:

```bash
cd /opt/guab-server
sudo docker compose down
sudo tar -xzf backups/world-YYYYMMDD-HHMMSS.tar.gz
sudo docker compose up -d --wait --wait-timeout 420
```

The archive is rooted at the deploy directory, so extracting it restores
`data/world*`, `data/plugins` and `data/server.properties` in place.

**Manual commands on the VM**

```bash
sudo docker/install.sh                     # first-time install
sudo /opt/guab-server/deploy.sh            # no-op if already current
sudo /opt/guab-server/deploy.sh --check    # prerequisites only, change nothing
sudo /opt/guab-server/deploy.sh --dry-run  # show what would be deployed
sudo /opt/guab-server/deploy.sh --force    # re-apply the latest release
sudo /opt/guab-server/rollback.sh          # revert; sticks until the next push
sudo /opt/guab-server/backup.sh            # on-demand backup
touch /opt/guab-server/.deploy-paused      # pause deploy/rollback (reason on line 1)
```

## Rickroll

`GuabRickroll` marks blocks with `/rickroll setblock`. Stepping on one has a **5% chance** (`chance: 0.05`) of sending the player to a hidden cinema for the video and song. While it plays, the lyrics appear above the hotbar and a pink boss bar counts down the song. Use `/rickroll test` to rickroll yourself, or `/rickroll test <player>` as staff.

- **Commands.** `/rickroll setblock|remove|test [player]|stop [player|all]|stopall|pause|resume|status [player]|pack [player]|verify|reload`. `/rickroll stop` lets a victim end their own show without staff; `/rickroll status` shows the chance, cooldown, pack id/hash and the caller's opt-out/reduced-motion state. `/rickroll pack [player]` re-requests the pack, and `/rickroll verify` checks every frame model/texture, duration, SHA-1, sound event and the live screen.
- **Pause and opt-out.** `/rickroll pause` stops natural triggers while leaving `/rickroll test` available; `/rickroll resume` re-enables them. Players can use `/settings` (or `/hud rickroll on|off`) to opt out of natural triggers, and `/hud rickroll-motion on|off` for reduced motion, which keeps the film and lyrics but skips the camera dolly and flashing effects. Staff tests still work on opted-out players.
- **When it can fire.** Natural triggers are skipped in the `guab_darts`, `guab_casino` and `guab_cinema` worlds, for spectators and creative players, during the join/teleport grace period, while paused, and when the player opted out.
- **Pack handshake.** The server also serves `guab-items.zip`, so pack status events are UUID-scoped: only the UUID most recently requested for that player is accepted. Status events from the items pack are ignored instead of being mistaken for the rickroll pack.

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

## Prank blocks

`GuabBlocks` provides the parkour's fake, potion and swap blocks. Staff stand on
or look at a block and tag it with `/guabblocks add <fake|potion|swap>`; the
other subcommands are `remove [world] <x> <y> <z>`, `info`, `test [player]`,
`set <chance|delay|cooldown|effect|duration|amplifier|sound> <value>`,
`list [page]`, `tp [world] <x> <y> <z>` and `reload`. Fake blocks restore
themselves after the configured delay and on shutdown, and per-player/per-block
cooldowns stop a block firing twice while someone stands still. `guab.blocks.bypass`
makes a player immune to triggers.

## Points

`GuabCore` owns the shared wallet.

- Your balance appears in the sidebar, the tab-list header and under your nametag, refreshed every second.
- Point gains and losses play a short pickup/bass sound and a green `+50` / red `-25` action bar; rapid changes are netted over a quarter second so a bet and its payout read as one result.
- The tab header supports `%area%` (`Casino`, `Darts`, `Cinema`, or `hud.area.default`) and the sidebar/nickname balance hides while spectating or watching the cinema.
- Points lines carry a small coin icon from the Guab bitmap font (`guab:hud`). Set `hud.font-icons: false` in `GuabCore/config.yml` to fall back to plain text if the resource pack is not installed.
- `/points` (aliases `/guabpoints`, `/bal`, `/balance`) shows your balance with your rank and the points gained or lost this session. `/points top [count]` and `/top [count]` (aliases `/pointstop`, `/pointleaderboard`) show the leaderboard; the count is clamped to 1–20.
- `/points history [player]` lists recent changes newest-first with the time, amount and before/after balance. Looking at another player's history needs `guab.points.others`.
- `/points pay <player> <amount>` sends your own points to an online or known player. It refuses self-payments, non-positive amounts and overdrafts, and needs `guab.points.pay`.
- Staff with `guab.points.admin` can use `/points set|add|take <player> <amount>` and `/points reset <player|all>`. A reset first shows a 30-second `[Confirm]`/`[Cancel]` prompt instead of wiping a balance immediately.
- Every staff mutation, payment and reset is written to the server log with an `[audit]` prefix, including the actor and the before/after balance.
- `/guabcore save|status|reload` (alias `/gcore`) needs `guab.core.admin`. `save` flushes points, stats and player preferences immediately; `status` reports the ledger, stat player count, save interval, HUD state and Vault; `reload` reapplies dynamic config and restarts the save timer. HUD `enabled` and `update-ticks` changes still need a restart; sidebar lines, tab text and welcome messages apply on reload.
- `/hud` (alias `/sidebar`) with no arguments still flips the sidebar. `/hud [sidebar|tab|nametag|sounds|actionbar|titles|rickroll-motion|rickroll] [on|off|toggle]` toggles one HUD or feedback channel; `/settings` (aliases `/prefs`, `/preferences`) opens the clickable summary. Preferences are stored per player and survive relogs.
- Hiding the sidebar leaves the nametag balance in place. `rickroll` opts out of natural rickrolls and `rickroll-motion` enables reduced motion; both default to off.
- `/stats [player]` (aliases `/tracker`, `/records`) shows deaths, void deaths, kills, rickrolls and parkour uses, and `/stats top [metric] [count]` shows a leaderboard. Your own stats need `guab.stats.use`; other players and leaderboards need `guab.stats.others`.
- `/spawn` (alias `/sp`) and `/hub` (aliases `/lobby`) teleport to the configured spawn; `/hub` is currently the same point. Both need `guab.spawn.use`.
- Joining prints your balance and clickable `[Open menu]` and `[Settings]` buttons. There is no `[Enter casino]` button any more.
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
   /casino status    # admin-only: confirms MineGames, Vault and the points ledger are hooked up
   ```

   `/casino build` **preserves** the hand-built Wetherspoons footprint (`x -24..-16`, `z -16..-10`); it does not create the pub. On a fresh world the pub must be built manually or restored from a snapshot, and the command's output says `pub area preserved`.

3. Stand on each pad below and run its create command.
4. Open the event with `/casino open`, or set `event.enabled: true` under `GuabGambling` and run `/casino reload`.

`/casino open` and `/casino close` are admin-only; they flip and save
`event.enabled`, reload the plugin and broadcast the change. `/casino close`
blocks new blackjack hands and the dealer, but hands already in play can still
hit, stand or double and settle normally. `/casino status` is also admin-only
and reports the world, event, blackjack GUI, active hands, dealer, MineGames,
Vault and the points ledger.

Players then walk in with `/casino` (alias `/gcasino`). `/casino leave` (alias `/casino exit`)
closes any open menu and returns them to `venue.exit-*`, or the default world
spawn when that is blank. The venue blocks non-admin building and cancels
damage and hunger.

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

A dedicated **Blackjack Dealer** NPC stands in the main casino hall near the bar at `-5.5, 65, -13`, outside the pub. Right-click the dealer (or run `/bj` with no arguments) to open a **54-slot double-chest table**. The dealer drives three screens:

- **Bet screen** — 1, 10, 100 and 1,000 chips, **All in**, **Reset** and **Repeat last bet**, plus the balance and table limits. Left-click a chip to add it; right-click subtracts. Press **Deal** to start.
- **Table screen** — both hands, the totals, the bet and balance, and **Hit**, **Stand**, **Double**, **History** and **Close**. Double is only available on the first two cards. Closing does not lose the hand; right-click the dealer again to reopen it.
- **Result screen** — the outcome, payout and net, then **Deal again** at the same bet or **Change bet** to return to the bet builder.

`/bj gui`, `/bj menu` and `/bj table` open the same chest explicitly. `/blackjack <bet>` (aliases `/bj`, `/21`) deals a hand directly, and `/bj hit`, `/bj stand`, `/bj double` and `/bj history` play it out from chat; the old chat buttons do the same. Blackjack pays 3:2 (`blackjack-payout: 1.5`), the dealer stands on 17, the shoe uses six decks, the minimum is 10 points and there is no maximum by default. Hands auto-stand after `blackjack.timeout-seconds` (120 seconds) and settle if the player disconnects.

`/new [bet]` deals another hand at your previous bet (or the table minimum if you have not played yet), so you do not have to retype the amount after every hand. Staff can move the dealer to their current position with `/blackjack setdealer` (also `/bj setdealer`).

If the plugin is disabled or redeployed while hands are in play, `Blackjack.stop()` refunds each active stake (including a doubled stake) before clearing the table, so a deploy cannot eat a player's points.

### Chicken

Players bet on red, blue, gold or green, then either cash out at the live multiplier or ride the lightning for the 4.6× colour bonus. The 7×7 board has 10 black (dead) tiles, so the chance of landing on the chosen colour is **39/196 ≈ 19.9%**. The chicken's multiplier climbs as it walks and golden pickups add to it, so cashing out early is a real choice rather than a flat refund. Winners are paid even if they disconnect before the round resolves.

If the chicken sticks at `1.00×`, run `sudo docker/fix-minegames-chicken.sh` on the VM. It restores `multiplier-increase-per-second: 0.03` and `pickup-multiplier: 0.25`, then reloads MineGames.

### Wetherspoons

A small Wetherspoons pub sits in the north-west corner of the casino hall (`x -24..-16`, `z -16..-10`). Right-click **any barrel in the pub** to open the 26.3 beer menu and choose exactly what you want; the menu buttons buy the pint directly. Shift-right-click a barrel for a quick random **1-point house pint**, or run `/beer` / right-click a menu shelf to open the same picker.

The pub is a **hand-built structure**. `/casino build` leaves its footprint untouched, but it does not recreate the walls, bar, shelves, dartboard or tap, so a fresh world needs the pub restored from a snapshot or rebuilt by hand.

The menu has **16 branded pints** across six styles: Lager, Stout, Ale, Cider, Alcohol-Free and the lethal **Baileys and Lime**. Every pint carries its own real brand name and matching bottle/can picture in the inventory — Stella, Guinness, Carling, Peroni, Strongbow and the rest — instead of all saying just “Beer”. Each style still has its own ABV, price, flavour lines and potion effects. Beer stacks to **64 per brand**. Drinking is a real 26.3 `CONSUMABLE` component, and left/right-click handling is instant so a quick click always drinks. Old tagged pints are migrated to their branded item the first time they are used or when the player logs in.

Staff can hand out stacks with `/guabitems give <player> beer 64` (alias `/gi give`), a specific brand with `/guabitems give <player> beer:stella 64`, or a style with `/guabitems give <player> beer:stout 64` (styles pick a random brand).

The item textures live in `guab-items.zip` on the pack host (`:39002`) and are applied as the server resource pack; the same assets are embedded in the rickroll pack so beer stays textured after a show. The branded pint textures are real product photos from Open Food Facts (CC BY-SA 3.0) where a clean cutout exists, the Baileys bottle is a public-domain Wikimedia Commons photo, and the dartboard face is Wikimedia Commons' unlabelled dartboard (CC BY-SA 3.0). Brands whose OFF photo is a scene shot, plus the generic house pint, are drawn procedurally so they still look like a clean 3D pint.

The same packs also carry the HUD bitmap font (`assets/guab/font/hud.json` plus `assets/guab/textures/font/hud.png`) and four custom sound events. Regenerate those source assets deterministically with:

```bash
python3 tools/build-guab-hud-assets.py
```

That writes the font PNG and the four OGG files under `pack/hud/`. It needs Pillow, numpy and ffmpeg. Then rebuild both packs with:

```bash
sudo tools/build-guab-items-pack.py
```

It copies the HUD assets into both zips, merges the custom sound events into `assets/guab/sounds.json` without removing the `guab:rickroll` entry, and validates that every referenced OGG exists. It writes `/tmp/guab-pack/guab-items.zip` and `/tmp/guab-pack/rickroll.zip` and prints their SHA-1 values. Copy them into `/opt/guab-server/resourcepack/`, update `resource-pack-sha1` in `data/server.properties` and `sha1` in `data/plugins/GuabRickroll/pack.properties`, then restart Paper. Run this after `tools/build-rickroll-pack.sh`, because the rickroll script recreates the base zip.

Beer items use generated 3D models: each 4-pixel row of the 64×64 texture becomes a 1-unit-tall cuboid following the alpha silhouette, with 1.5–4 units of depth, label art wrapped around the sides, and full GUI/hand/ground display transforms. The builder flood-fills photo backgrounds with tolerance, keeps the largest connected component, pads/crops the subject, and falls back to a brand-coloured procedural can/bottle (or a drawn house pint) when a photo has no usable cutout. Both zips get the generic `guab:beer` pint and every brand model/texture; item→model and texture→PNG references, coordinates (0–16) and UVs (0–16) are validated before zipping, and sorted JSON plus fixed ZIP timestamps keep hashes reproducible. Pass `--preview DIR` for flat depth-offset previews.

**Pub features**

- **Weighted drunk meter** — every pint contributes real alcohol units based on ABV and volume. They decay over the configured window (15 minutes by default); the action bar shows `Tipsy → Merry → Drunk → Wrecked → Absolutely Wetherspoons`, with escalating nausea, slowness, blindness and the occasional hangover. Effects stay on continuously at your current tier instead of blinking off between refreshes; they step down as the meter drops, and disappear when you sober up. Milk clears the meter and all drunk effects immediately.
- **Pints leaderboard** — lifetime pints and units are saved to `pints.yml`, `/pints [top|units]` lists them with clickable **Top pints** / **Top units** buttons, and a hologram above the bar shows the top three plus total pints served.
- **Milestones** — the 1st, 10th, 25th, 50th and 100th lifetime pint each pay a one-off points reward.
- **`/round [beer]`** — buys that brand (or a random brand from a style) for everyone within 14 blocks of the pub centre. It first posts a **cost preview** with clickable **Confirm** / **Cancel** buttons and a 30-second expiry; no points are taken until `/round confirm`. The default house round stays 1 point each.
- **`/beer`** (aliases `/pint`, `/spoons`) — opens the dialog menu; `/beer list` shows every branded pint with clickable buy buttons, `/beer buy <beer> [amount]`, `/beer milk`, `/beer stats` and `/beer again [amount]` (repeat your last order, optionally with a new amount).
- **Milk** — sold from the bar menu for 5 points. It is only available when `beer.milk.enabled` is true, needs `guab.pub.use`, refuses spectators and players who are already sober, and is blocked outside the pub unless the player has `guab.pub.menu.remote`. Drinking it clears the drunk meter and the nausea/slowness effects, so you can sober up without waiting.
- **Pub setup commands** — staff with `guab.items.admin` can use `/guabitems tapinfo` (tap block and radius), `/guabitems status` (pub centre, tap, board, shelves and worlds), and `/guabitems shelf <add|remove>` while looking at a shelf block.
- **Baileys and Lime** — a 3-point cursed cocktail. Drinking it kills you instantly with a custom death message, and it is deliberately excluded from the random house tap. It still counts as a pint on the leaderboard.
- **Pub quiz** — every 10 minutes (or `/quiz`) a question goes out in chat; the first correct answer wins 100 points.
- **Beer menu shelves** — right-click a shelf to open the dialog menu; left-click still refuses to let anyone take the display bottles.
- **Carpet** — the pack overrides `red_carpet` with the Wetherspoons-style pattern, so the pub rug and the casino aisles share it.

### Darts

The pub target block is the **entrance** to a dedicated darts room. Right-click it, pick **101, 301 or 501** (default **101**), and you are teleported to a private oche with a bow and 64 arrows. The command is `/darts` (alias `/dart`). Shoot the target block on the far wall; the arrow impact is scored on the real board geometry.

**How to play:** right-click the pub dartboard, pick a leg, then hold right-click to draw the bow and release to shoot the target. You are locked to your oche, so you cannot walk closer or further away. Shift-right-click the pub board for a fast default **101** leg. Three arrows per visit. Beginner assist is on by default: no busts, and any dart can finish on exactly zero. With `pub.darts.beginner-assist: false`, the last arrow must be a double (bullseye 50 counts). `/darts help` prints the matching rules, `/darts quit` returns you to the pub, and the checkout hint appears on the action bar.

- **Quick commands.** `/darts again` (aliases `/darts rematch`, `/darts replay`) starts the last mode/legs again; `/darts practice [101|301|501]` starts a practice leg with no stats or rewards; `/darts status` shows your score, darts left, opponent, drunk tier and whether you are away from the oche.
- **Records.** `/darts stats [player]` works for offline players from `darts.yml` and needs no admin permission. `/darts top [legs|180s|checkout|best]` lists the leaderboard.
- **Resume dialog.** Right-clicking the board while a leg is live opens a contextual dialog with **Resume**, **Quit** (a forfeit in a 1v1) and **My stats** instead of starting a second leg.
- **Capacity and forfeits.** The room has four oches, and a 1v1 claims two; a fifth player is told the room is full. Leaving a 1v1, `/darts quit` during one, or disconnecting mid-leg is a forfeit, and the opponent is credited with the win.

- **Real arrows.** The arrow sticks in the board for a moment, and chat tells you exactly what you hit (`T20`, `D16`, `Bullseye 50`, `Miss`). The 3×3 board uses the twenty standard sectors, plus singles, doubles, trebles, outer bull (25) and bullseye (50). Hitting T20 shows a **TRIPLE 20 / 60** title in the middle of the screen.
- **Dedicated room.** The `guab_darts` world has a lit oche, a 3×3 target, a bow, arrows, and no mobs or PvP. Movement is locked while the leg is live. You are returned automatically after the match or with `/darts quit`.
- **Shared hit hologram.** A hologram between the oches shows every active player's current score and last hit, so you and a friend can see each other's darts.
- **Proper legs.** Each visit is three arrows and the score counts down. With beginner assist on (the default), an impossible finish is ignored rather than busting the whole visit and any dart can check out; with it off, a bust overshoots, leaves 1 or finishes on a non-double, and the last arrow must be a double (bullseye 50 counts).
- **Checkout hints.** The action bar and the room display show the shortest finish, e.g. `T20 T20 BULL` for 170.
- **Drunk wobble.** Every pint in the last window adds Gaussian aim error, so the target genuinely gets harder as the night goes on.
- **Career records.** `darts.yml` stores legs played/won, darts thrown, best leg, 180s, checkouts, highest checkout and best visit. `/darts stats [player]` and `/darts top [legs|180s|checkout|best]` read it.
- **Match night.** `/darts start [101|301|501] [legs]` starts directly. Checkouts pay points based on the mode, darts used and checkout value, capped by config. 180s get fireworks and a title; checkouts get a celebration and a pub-wide announcement.
- **Admin.** `/darts board` sets the pub entrance from the block you are looking at; `/darts reload` reloads the rules.

The pub entrance, face, ring sizes, wobble, rewards and cooldowns are all in `pub.darts` in `GuabItems/config.yml`.

### Snapshots

`/casino snapshot` (admin-only) writes `data/backups/casino-snapshot-<timestamp>.zip` containing `points.yml`, the GuabCore/GuabGambling configs and every MineGames YAML. It first flushes GuabCore (`/guabcore save`), so `points.yml` is current rather than up to a minute behind the table. `/casino snapshot list` lists the existing snapshots newest-first with their size. It does not include the venue world or the hand-built pub; use the world backup for those.

Restore a snapshot with Paper stopped (or no one online), from the data directory so the zip's `plugins/...` paths land in the right place:

```bash
cd /opt/guab-server/data
sudo unzip -o backups/casino-snapshot-YYYYMMDD-HHMMSS.zip
sudo docker compose -f /opt/guab-server/docker-compose.yml restart mc
```

The deploy restarts Paper and installs the pinned third-party jars from
`/opt/guab-server/third-party` after every swap. Take a snapshot before a
redeploy if you are about to change points or table config; the daily backup
covers the rest.

### Third-party jars

Jars are pinned in `docker/install-casino.sh` with SHA-256 checks and installed to `/opt/guab-server/third-party`. `deploy.sh` reinstalls them after every jar swap.

## Battle pit

`GuabMinigames` runs a free-for-all pit in the overworld at `100, -61, 100` (built into the flat world, 19×19, walls four blocks high). The command is `/battlepit` (aliases `/pit`, `/bp`).

- `/battlepit join` teleports you in; `/battlepit leave` or `/battlepit quit` returns you to the pit exit. With `battlepit.exit-mode: join`, leave instead returns you to the exact spot where you joined. Auto-leaving by walking or teleporting out restores your inventory but never teleports you back.
- Kills pay **50 points**, deaths cost **25**, and the action bar shows the current kill streak. The kill feed includes the weapon and the victim's coordinates (`at world x, y, z`).
- `/battlepit status` shows the open state, spawn/exit, `exit-mode`, rewards and who is fighting. `/battlepit stats [player]` and `/battlepit top [kills|deaths|streak]` show session records.
- `/kit` gives the tagged iron PvP loadout (sword, bow, arrows, shield, food, blocks and pickaxe) with a 30-second cooldown. Joining or leaving resets the cooldown; tagged gear cannot be dropped or placed.
- Your inventory is saved on join and restored on leave, disconnect and graceful stop. If a crash or restart leaves a save behind, it is restored when you next join. Inventory is kept on death in the pit.
- `/battlepit setspawn [x y z world]`, `setexit`, `kick <player>`, `purge` and `reload` need `guab.battlepit.admin`.
- Spawn protection, arrow-hit HP, cobblestone decay and the respawn bell are configurable under `battlepit`, `kit` and `respawn-bell` in `GuabMinigames/config.yml`.

**Parkour.** `/parkour` (alias `/pk`) teleports to the configured start and remembers your return point. `/parkour quit` returns there, `/parkour restart` starts again from the configured start without losing the saved return point, and `/parkour status` prints the world, start and your return point. `/parkour set` and `/parkour reload` need `guab.parkour.admin`.

## Permissions

The gameplay permissions are enforced in code as well as on the commands, so a player without a permission gets a clear message rather than a silent no-op.

| Permission | Default | Covers |
| ---------- | ------- | ------ |
| `guab.points.pay` | true | `/points pay <player> <amount>` |
| `guab.points.others` | op | `/points <player>` and `/points history [player]` |
| `guab.points.admin` | op | `/points set\|add\|take\|reset`, including `/points reset all` |
| `guab.stats.use` | true | Your own `/stats` |
| `guab.stats.others` | true | `/stats <player>` and `/stats top` |
| `guab.spawn.use` | true | `/spawn`, `/sp`, `/hub` and `/lobby` |
| `guab.core.admin` | op | `/guabcore save\|status\|reload` (`/gcore`) |
| `guab.blocks.admin` | op | `/guabblocks add\|remove\|info\|test\|set\|list\|tp\|reload` |
| `guab.blocks.bypass` | false | Never trigger fake, potion or swap blocks |
| `guab.items.admin` | op | `/guabitems give\|reload\|tapinfo\|status\|shelf` |
| `guab.items.use` | true | The tagged prank items (wind charge, jump feather) only |
| `guab.pub.use` | true | Beer, milk, `/beer`, `/round`, `/pints`, the tap and shelves |
| `guab.pub.protection.bypass` | op | Break the pub's protected tap, shelves and dartboard |
| `guab.pub.menu.remote` | op | Order beer or milk outside the pub radius |
| `guab.pub.darts.use` | true | The dartboard, `/darts` legs, practice, 1v1 invites and records |
| `guab.pub.darts.admin` | op | `/darts board\|reload` |
| `guab.pub.quiz.start` | op | Start a pub quiz question with `/quiz start` |
| `guab.pub.quiz.admin` | op | Stop, skip and inspect the pub quiz |
| `guab.menu.use` | true | The native `/guab` hub (`/menu`) |
| `guab.casino.use` | true | `/casino enter`, blackjack, `/new` and the dealer NPC |
| `guab.casino.admin` | op | `/casino open\|close\|build\|setup\|dispatch\|setspawn\|status\|snapshot\|reload` and `/blackjack setdealer` |
| `guab.casino.build` | op | Changing blocks inside `guab_casino` |
| `guab.battlepit.use` | true | Join/leave, status and session stats for the battle pit |
| `guab.battlepit.admin` | op | `setspawn`, `setexit`, `kick`, `purge` and `reload` |
| `guab.parkour.use` | true | `/parkour`, `quit`, `restart` and `status` |
| `guab.parkour.admin` | op | `/parkour set` and `/parkour reload` |
| `guab.kit.use` | true | `/kit` |
| `guab.killplane.admin` | op | `/killplane on\|off\|set\|world\|reload` |
| `guab.killplane.status` | true | `/killplane status` without admin rights |
| `guab.killplane.bypass` | false | Never be killed by the kill plane |
| `guab.rickroll.admin` | op | `/rickroll` management, other players' shows and verification |

`guab.items.use` no longer gates beer; a player with `guab.items.use` but without `guab.pub.use` cannot buy or drink at the pub. `/rickroll stop` on your own show and `/rickroll status` for yourself work without `guab.rickroll.admin`; staff-only actions check it in code.

## Web console

`GuabWebConsole` exposes a token-protected HTTP endpoint so a command can be run with `curl`. It binds to **loopback** (`127.0.0.1:39100`) by default, and the compose file publishes only `127.0.0.1:39100`; reach it through an SSH tunnel:

```bash
ssh -N -L 39100:127.0.0.1:39100 user@145.241.198.214
```

Prefer the header token over the legacy query token:

```bash
curl -H "Authorization: Bearer SECRET" \
     "http://127.0.0.1:39100/command?cmd=list"
```

The POST body is read only after the IP check, rate limit and token check, and is capped at `web.max-body-bytes` (16,384 by default); commands are capped at `web.max-command-length` (1,000). See `plugins/guab-webconsole/README.md` for the full endpoint list, config and the Docker bind caveat.

## Adding a plugin

1. Add `"plugins:guab-name"` to `settings.gradle.kts`.
2. Copy an existing module folder, then rename the package, main class and `plugin.yml`.
3. Add `depend: [GuabCore]` to `plugin.yml` if it needs the points ledger.
