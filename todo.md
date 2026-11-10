# Plugin TODOs

Last updated: 2026-11-10

Overall: `█████████░` **93%** (91/98 checklist items)

| Plugin            | Progress                    |
| ----------------- | --------------------------- |
| guab-core        | `████████░░` 86% (12/14)    |
| guab-blocks      | `██████████` 100% (10/10)   |
| guab-items       | `██████████` 100% (16/16)   |
| guab-rickroll    | `█████████░` 92% (22/24)    |
| guab-gambling    | `█████████░` 88% (14/16)    |
| guab-minigames   | `█████████░` 94% (17/18)    |

Progress is per checklist line, updated by hand.

The loop: **minigames earn points → gambling risks them → items and blocks are the rewards.**

## guab-core — `████████░░` 86% (12/14)

**What it does:** the shared foundation. Holds the player points ledger (the parkour currency), the shared stats tracker and common helpers. Every other plugin depends on it.

Status: live. `PointsService` is registered, `/points` and `/points top` work, the ledger backs the Vault economy, and the sidebar carries a top-3 leaderboard. Balances flush every 60 seconds. The shared stats tracker persists deaths, kills, rickrolls and parkour uses in `stats.yml`. `/settings` and `/hud` now persist per-player HUD, sound, title, rickroll and reduced-motion preferences; `/points` shows rank and session delta, and staff mutations are audit-logged. `/guabcore save|status|reload` gives operators a manual flush, state summary and safe config reload.

- [x] `PointsService` interface: `get`, `set`, `add`, `take`, `top`
- [x] YAML-backed store (`points.yml`), clamp at zero
- [x] Register the service in `ServicesManager` so other plugins can load it
- [x] Async save every minute (60s) + save on disable
- [x] `/points` command (self balance, staff can inspect others, `/points top` leaderboard)
- [x] Vault `Economy` bridge over the ledger (offline refunds, integer points)
- [x] Sidebar balance, top-3 leaderboard, online count and tab-list header
- [x] Shared stats tracker (`stats.yml`, `/stats`, `/stats top`) for deaths, kills, rickrolls and parkour
- [x] `/settings` clickable summary and `/hud` per-feature toggles (sidebar, tab, nametag, sounds, actionbar, titles, rickroll-motion, rickroll)
- [x] `/points history|pay|reset`, rank/session delta and `[audit]` logging
- [x] `/top`, `/spawn` and `/hub` shortcuts
- [x] `/guabcore save|status|reload` (alias `/gcore`, `guab.core.admin`)
- [ ] Shared cooldown helper and message helpers
- [ ] Decide if core also owns the parkour scoreboard

## guab-blocks — `██████████` 100% (10/10)

**What it does:** the parkour's prank blocks. Staff tag blocks; stepping on one can drop the player through the floor, hit them with a random bad potion effect, or swap them with another player.

Status: implemented and built. `/guabblocks add|remove|info|test|set|list|tp|reload`, `blocks.yml`, fake/potion/swap effects, per-player and per-block cooldowns, and fake-block restore on disable. Not yet exercised in-game.

- [x] `blocks.yml` store keyed by `world;x;y;z`
- [x] `/guabblocks add <fake|potion|swap>`, `remove`, `list`, `reload`
- [x] Tab completion + permission
- [x] Fake block: fall-through chance, restore delay, break sound
- [x] Potion block: random effect from a list, duration and amplifier
- [x] Swap block: teleport victim to a random player, cooldown
- [x] Trigger on stepping (block under feet, change-of-block only)
- [x] Per-player and per-block cooldowns
- [x] Config for chances/durations; restore fake blocks after a show
- [x] Make sure blocks do not fire twice while standing still

## guab-items — `██████████` 100% (16/16)

**What it does:** the prank items, Wetherspoons pub, beer, milk and darts room.

Status: live. PDC-tagged items, wind charge, jump feather, `/guabitems give`, main/off-hand handling, cooldowns and anvil/craft guards. The pub has 16 branded pints, milk, lethal Baileys and Lime, a 71-question quiz, a bartender and a dedicated darts room. `/guab` is the native hub with Pub, Casino, Darts, Parkour, Battle Pit, Stats and Help pages; the old quest page is gone. Darts defaults to 101 with beginner assist (no busts, any dart can finish) and now has quick replay, practice, status, offline records, a contextual resume/quit dialog and four-oche capacity with forfeits.

- [x] PDC-tagged items so names cannot be faked
- [x] Wind charge item: boost self forward, knock nearby players away
- [x] Jump boost feather: permanent jump boost, consumed on use
- [x] `/guabitems give <player> <wind_charge|jump_boost> [amount]`
- [x] Right-click handling for main and off hand
- [x] Cooldowns and config values
- [x] Block crafting/renaming from producing fake copies
- [x] Wetherspoons menu with 16 branded pints, milk, lethal Baileys and constant drunken effects
- [x] Darts room with bow, 3x3 board, oche lock and shared live-hit hologram
- [x] Beginner darts: 101 default, no busts, any dart can finish, wider rings
- [x] Native `/guab` dialog hub with Pub, Casino, Darts, Parkour, Battle Pit, Stats and Help
- [x] Parkour hub button and `/guab parkour` teleport to the configured start
- [x] Bartender moved behind the bar in the expanded pub
- [x] Pub quiz expanded to 71 general and beer questions
- [x] Pub QoL: `/beer again [amount]`, `/round` cost preview + confirm/cancel, clickable `/beer list` and `/pints`, milk rules, and admin `/guabitems tapinfo|status|shelf`
- [x] Darts QoL: `/darts again|rematch|replay`, `/darts practice`, `/darts status`, offline `/darts stats <player>`, resume/quit dialog, four-oche capacity and forfeit handling

## guab-rickroll — `█████████░` 92% (22/24)

**What it does:** the 5% joke. A tagged block sends the victim to a hidden cinema, locks them in spectator, and plays the rickroll video and song, then puts them back exactly where they were.

Status: live. 60-second show cut from 0:19, 12 fps, shared 256-color palette, synced lyrics, notched boss bar, brighter cinema, disconnect restore, and a 5%/5 min trigger. Shows are counted in the shared stats tracker. Post-effect blur is off; the brief tracking flash remains. `/rickroll status|pack|pause|resume` and self-stop are in, opt-out/reduced motion come from `/settings`, the pack handshake is UUID-scoped, and `/rickroll verify` checks all frames, duration, SHA-1 and the live screen.

- [x] 5% trigger block, per-player cooldown (300s)
- [x] `/rickroll setblock`, `remove`, `test`, `stop`, `stopall`, `verify`, `reload`
- [x] Hidden void cinema world + room
- [x] ItemDisplay screen with per-frame `ITEM_MODEL` swapping
- [x] Resource pack build script + pack server (`:39002`)
- [x] Shared-palette 256-color pipeline (dedupe, oxipng/advpng, `zip -9 -X`)
- [x] SHA-1 verification, frame count/fps/duration via `pack.properties`
- [x] Spectator lock, per-player midnight, restore on stop/quit/restart
- [x] Fallback when the pack is declined
- [x] 60-second show from 0:19 (single cut, 12 fps, shared 256-color palette, compact pack)
- [x] Visual QA: video, texture path, audio on the old 6-block screen
- [x] Synced lyrics on the action bar (LRC timestamps)
- [x] Boss-bar countdown timer (0:60 to 0:00, notched)
- [ ] Check the new 10-block screen, red frame, lights, and stair direction
- [x] Mark the first block in-game with `/rickroll setblock`
- [ ] Full 60-second show test, including disconnect mid-show
- [x] Tune `cinema.width`/`height`, `chance`, `cooldown-seconds` (20/11.25, 5%, 5 min)
- [x] Report every show to the shared stats tracker
- [x] Decide full video vs loop: 60-second cut, no loop
- [x] VHS tracking beat, projector sounds and a short particle burst
- [x] `/rickroll status|pack|pause|resume` and self-stop
- [x] `/settings` opt-out and reduced-motion preference; staff tests still work
- [x] UUID-scoped pack handshake ignores `guab-items.zip` status events
- [x] `/rickroll verify` checks all frames, duration, SHA-1, sound event and screen

## guab-gambling — `█████████░` 88% (14/16)

**What it does:** the point sink. A walk-in casino for the one-night event: MineGames runs the physical tables (roulette, slots, mines, chicken, mob fights, dice) on top of the Guab points ledger, inside a venue built by this plugin.

Status: live in production. Venue built, all six station types placed, event open, points flowing through Vault. Bets are uncapped, roulette idles until the first wager, fights preview frozen fighters with their real gear, holograms no longer duplicate, slots run at roughly 92% RTP, chicken pays offline winners, and the blackjack table now has a 54-slot chest GUI. `/casino close` stops new hands and the dealer while in-flight hands finish.

- [x] Load `PointsService` from GuabCore
- [x] Vault economy bridge so MineGames spends and refunds Guab points
- [x] Walk-in venue: `/casino build|setup|dispatch|status|reload|setspawn|leave|exit|open|close`, protected world
- [x] Pin VaultUnlocked + MineGames in the deploy pipeline
- [x] Place stations: roulette, three slots, mines, chicken, fight arena
- [x] Event limits: no per-bet cap, `/casino` open to everyone, 1,000-point first-join stake
- [x] Roulette idles until the first wager
- [x] Fight preview: fighters spawn frozen with their real gear during betting
- [x] Fix fight holograms duplicating across chunk unloads
- [x] Retune slots: 8 weighted reels (3 diamonds), 1.5x pair / 10x triple, ~92% RTP
- [x] Retune chicken: 4.6x colour bonus, live multiplier and pickups, offline winners paid
- [x] 54-slot blackjack chest GUI (bet/table/result, chips, Deal, Hit/Stand/Double, Deal again/Change bet) and `/bj gui|menu`
- [x] `/casino leave|exit`, `/casino open|close` and closed-event gating for new hands/dealer; in-flight hands still settle
- [x] `/casino snapshot [list]` flushes GuabCore first and lists existing snapshots
- [ ] Rehearse with two accounts, including a disconnect mid-round refund
- [ ] Teardown: remove stations, close the casino

Event-night ops: pause auto-deploy with `.deploy-paused`, snapshot `points.yml`, and close with `/casino close` (or `event.enabled: false` + `/casino reload`).

## guab-minigames — `█████████░` 94% (17/18)

**What it does:** the point faucet. Battle pit and other small games outside the main parkour award points for kills and wins, which then feed gambling and item rewards.

Status: live. Battle pit built in the overworld at `100, -61, 100` with join/leave/quit, kill rewards, death penalties, pit respawn, a streak action bar, a tagged PvP kit, saved inventories, session stats/top/kick/purge, a configurable exit mode and a coordinate kill feed. `/parkour` teleports to the parkour start and supports quit/restart/status.

- [x] Battle pit arena spawn (`/battlepit setspawn`)
- [x] `/battlepit join` / `leave`, teleport in and out
- [x] Track who is in the pit
- [x] Kill reward and death penalty via `PointsService`
- [x] Respawn at the pit spawn, keep or drop inventory config
- [x] Action bar/scoreboard for the current streak
- [x] Build the battle pit arena platform, walls and barrier lip at `100, -61, 100`
- [x] `/kit` PvP loadout with iron gear, bow, arrows, shield, food, blocks and pickaxe
- [x] Smart inventory save/restore on battle pit join, leave, disconnect and graceful stop
- [x] `/parkour` teleport command with configurable start
- [x] Respawn bell sound for every player respawn
- [x] Kill plane below the play area (`/killplane`, default Y -66)
- [x] Cobblestone placed in the arena decays after 15 seconds
- [x] Spawn protection after joining or respawning in the pit
- [x] Arrow hits show the victim's remaining HP
- [x] Battle pit QoL: `/battlepit stats|top|kick|purge|quit`, kit tagging/reset, saved inventories, `exit-mode`, coordinate kill feed
- [x] Parkour QoL: `/parkour quit|restart|status`
- [ ] Additional point-earning minigames outside the main parkour

## QoL pass (2026-11-10)

Done in this pass:

- **Blackjack:** 54-slot double-chest dealer UI with bet, table and result screens; chips, Deal, Hit/Stand/Double, Deal again/Change bet; `/bj gui|menu`.
- **HUD and settings:** `/settings` clickable summary and `/hud` toggles for sidebar, tab, nametag, sounds, actionbar, titles, rickroll reduced motion and rickroll opt-out.
- **Points and core ops:** `/top`, `/spawn`, `/hub`, `/points history|pay|reset`, rank/session delta, confirm/cancel resets and audit logging; `/guabcore save|status|reload` flushes stores and inspects/reloads core config.
- **Snapshots:** `/casino snapshot [list]` flushes GuabCore before zipping and lists existing snapshots.
- **Pub and darts:** `/beer again`, round preview/confirm, clickable menus, milk rules, admin tap/status/shelf, darts replay/practice/status, offline stats, resume dialog, capacity and forfeits.
- **Battle pit and admin:** stats/top/kick/purge/quit, tagged kit reset, saved inventories, `exit-mode`, coordinate kill feed, parkour quit/restart/status.
- **Rickroll:** `status/pack/pause/resume`, self-stop, opt-out/reduced motion, UUID-scoped pack handshake, 5% default and full verify.
- **Deploy safety:** `stop_grace_period: 120s`, health-gated deploy with automatic restore, `.deploy-paused` guard, daily backups with plugin data and `server.properties`, `tools/verify.sh`.
- **Tests and CI:** 69 unit tests across 16 test classes; PR CI and deploy path filters; `tools/check-docs.py` docs check.

Remaining:

- Live server verification of the whole QoL pass (deploy, HUD, points, pub, darts, battle pit, parkour).
- Roulette was intentionally untouched; confirm the live station still behaves during the event rehearsal.
- Blackjack chest GUI in-game test with two accounts, including a disconnect mid-hand.
- Rebuild the rickroll pack from the current media and deploy the new SHA-1/hash.

## Platform

Decision (2026-11-05): stay on Paper. Pumpkin (Rust) was reviewed — it is pre-1.0, plugins are
WASM, and rickroll's features map reasonably well, but the API is unstable and the world/render
parity risk is not worth a cutover yet. Revisit when Pumpkin reaches 1.0, or run a parallel
PatchBukkit spike on a spare port if curiosity wins.
