# Plugin TODOs

Last updated: 2026-11-06

Overall: `█████████░` **90%** (71/79 checklist items)

| Plugin            | Progress                    |
| ----------------- | --------------------------- |
| guab-core        | `████████░░` 80% (8/10)      |
| guab-blocks      | `██████████` 100% (10/10)   |
| guab-items       | `██████████` 100% (10/10)   |
| guab-rickroll    | `█████████░` 85% (17/20)    |
| guab-gambling    | `█████████░` 86% (12/14)    |
| guab-minigames   | `█████████░` 93% (14/15)    |

Progress is per checklist line, updated by hand.

The loop: **minigames earn points → gambling risks them → items and blocks are the rewards.**

## guab-core — `████████░░` 80% (8/10)

**What it does:** the shared foundation. Holds the player points ledger (the parkour currency), the shared stats tracker and common helpers. Every other plugin depends on it.

Status: live. `PointsService` is registered, `/points` and `/points top` work, the ledger backs the Vault economy, and the sidebar carries a top-3 leaderboard. Balances flush every 60 seconds. The new stats tracker persists deaths, kills, rickrolls and parkour uses in `stats.yml`.

- [x] `PointsService` interface: `get`, `set`, `add`, `take`, `top`
- [x] YAML-backed store (`points.yml`), clamp at zero
- [x] Register the service in `ServicesManager` so other plugins can load it
- [x] Async save every minute (60s) + save on disable
- [x] `/points` command (self balance, staff can inspect others, `/points top` leaderboard)
- [x] Vault `Economy` bridge over the ledger (offline refunds, integer points)
- [x] Sidebar balance, top-3 leaderboard, online count and tab-list header
- [x] Shared stats tracker (`stats.yml`, `/stats`, `/stats top`) for deaths, kills, rickrolls and parkour
- [ ] Shared cooldown helper and message helpers
- [ ] Decide if core also owns the parkour scoreboard

## guab-blocks — `██████████` 100% (10/10)

**What it does:** the parkour's prank blocks. Staff tag blocks; stepping on one can drop the player through the floor, hit them with a random bad potion effect, or swap them with another player.

Status: implemented and built. `/guabblocks add|remove|list|reload`, `blocks.yml`, fake/potion/swap effects, per-player and per-block cooldowns, and fake-block restore on disable. Not yet exercised in-game.

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

## guab-items — `██████████` 100% (10/10)

**What it does:** the prank items, Wetherspoons pub, beer, milk and darts room.

Status: implemented and built. PDC-tagged items, wind charge, jump feather, `/guabitems give`, main/off-hand handling, cooldowns and anvil/craft guards. The pub now has 16 branded pints, milk, lethal Baileys and Lime, a 71-question quiz, and a dedicated darts room.

- [x] PDC-tagged items so names cannot be faked
- [x] Wind charge item: boost self forward, knock nearby players away
- [x] Jump boost feather: permanent jump boost, consumed on use
- [x] `/guabitems give <player> <wind_charge|jump_boost> [amount]`
- [x] Right-click handling for main and off hand
- [x] Cooldowns and config values
- [x] Block crafting/renaming from producing fake copies
- [x] Wetherspoons menu with 16 branded pints, milk, lethal Baileys and constant drunken effects
- [x] Darts room with bow, 3x3 board, oche lock and shared live-hit hologram
- [x] Pub quiz expanded to 71 general and beer questions

## guab-rickroll — `█████████░` 85% (17/20)

**What it does:** the 5% joke. A tagged block sends the victim to a hidden cinema, locks them in spectator, and plays the rickroll video and song, then puts them back exactly where they were.

Status: live. One-minute cut from 0:19 (720 frames, 60s, 7.62 MB pack), 12 fps, shared 256-color palette, synced lyrics, notched 1:00 boss bar, brighter cinema, disconnect restore, and a 65%/5 min trigger. Shows are counted in the shared stats tracker.

- [x] 5% trigger block, per-player cooldown (300s)
- [x] `/rickroll setblock`, `remove`, `test`, `stop`, `verify`, `reload`
- [x] Hidden void cinema world + room
- [x] ItemDisplay screen with per-frame `ITEM_MODEL` swapping
- [x] Resource pack build script + pack server (`:39002`)
- [x] Shared-palette 256-color pipeline (dedupe, oxipng/advpng, `zip -9 -X`)
- [x] SHA-1 verification, frame count/fps/duration via `pack.properties`
- [x] Spectator lock, per-player midnight, restore on stop/quit/restart
- [x] Fallback when the pack is declined
- [x] 60-second cut from 0:19 (AV1 source, 12 fps, 192x108, shared 256-color palette, 7.62 MB lossless)
- [x] Visual QA: video, texture path, audio on the old 6-block screen
- [x] Synced lyrics on the action bar (LRC timestamps)
- [x] Boss-bar countdown timer (1:00 to 0:00, notched)
- [ ] Check the new 10-block screen, red frame, lights, and stair direction
- [x] Mark the first block in-game with `/rickroll setblock`
- [ ] Full 1-minute show test, including disconnect mid-show
- [x] Tune `cinema.width`/`height`, `chance`, `cooldown-seconds` (20/11.25, 65%, 5 min)
- [x] Report every show to the shared stats tracker
- [x] Decide full video vs loop: 1-minute cut, no loop
- [ ] Optional: VHS static, scanlines

## guab-gambling — `█████████░` 86% (12/14)

**What it does:** the point sink. A walk-in casino for the one-night event: MineGames runs the physical tables (roulette, slots, mines, chicken, mob fights, dice) on top of the Guab points ledger, inside a venue built by this plugin.

Status: live in production. Venue built, all six station types placed, event open, points flowing through Vault. Bets are uncapped, roulette idles until the first wager, fights preview frozen fighters with their real gear, holograms no longer duplicate, slots run at roughly 92% RTP, chicken pays offline winners, and a chat blackjack table is live.

- [x] Load `PointsService` from GuabCore
- [x] Vault economy bridge so MineGames spends and refunds Guab points
- [x] Walk-in venue: `/casino build|setup|dispatch|status|reload|setspawn`, protected world
- [x] Pin VaultUnlocked + MineGames in the deploy pipeline
- [x] Place stations: roulette, three slots, mines, chicken, fight arena
- [x] Event limits: no per-bet cap, `/casino` open to everyone, 1,000-point first-join stake
- [x] Roulette idles until the first wager
- [x] Fight preview: fighters spawn frozen with their real gear during betting
- [x] Fix fight holograms duplicating across chunk unloads
- [x] Retune slots: 8 weighted reels (3 diamonds), 1.5x pair / 10x triple, ~92% RTP
- [x] Retune chicken: 4.6x colour bonus, live multiplier and pickups, offline winners paid
- [x] Blackjack table (`/blackjack`, `/bj`): dealer NPC with bet dialog, hit, stand, double, 3:2 naturals
- [ ] Rehearse with two accounts, including a disconnect mid-round refund
- [ ] Teardown: remove stations, close the casino

Event-night ops: freeze the auto-deploy timer, snapshot `points.yml`, and close with `event.enabled: false` + `/casino reload`.

## guab-minigames — `█████████░` 93% (14/15)

**What it does:** the point faucet. Battle pit and other small games outside the main parkour award points for kills and wins, which then feed gambling and item rewards.

Status: live. Battle pit built in the overworld at `100, -61, 100` with join/leave, kill rewards, death penalties, pit respawn, a streak action bar, a smart PvP kit and a respawn bell. `/parkour` teleports to the parkour start.

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
- [ ] Additional point-earning minigames outside the main parkour

## Platform

Decision (2026-11-05): stay on Paper. Pumpkin (Rust) was reviewed — it is pre-1.0, plugins are
WASM, and rickroll's features map reasonably well, but the API is unstable and the world/render
parity risk is not worth a cutover yet. Revisit when Pumpkin reaches 1.0, or run a parallel
PatchBukkit spike on a spare port if curiosity wins.
