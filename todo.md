# Plugin TODOs

Last updated: 2026-11-05

Overall: `█████░░░░░` **49%** (32/65 checklist items)

| Plugin            | Progress                    |
| ----------------- | --------------------------- |
| guab-core        | `████████░░` 78% (7/9)       |
| guab-blocks      | `░░░░░░░░░░` 0% (0/10)      |
| guab-items       | `░░░░░░░░░░` 0% (0/7)       |
| guab-rickroll    | `████████░░` 79% (15/19)    |
| guab-gambling    | `████████░░` 77% (10/13)    |
| guab-minigames   | `░░░░░░░░░░` 0% (0/7)       |

Progress is per checklist line, updated by hand.

The loop: **minigames earn points → gambling risks them → items and blocks are the rewards.**

## guab-core — `████████░░` 78% (7/9)

**What it does:** the shared foundation. Holds the player points ledger (the parkour currency) and common helpers. Every other plugin depends on it.

Status: live. `PointsService` is registered, `/points` and `/points top` work, the ledger backs the Vault economy, and the sidebar carries a top-3 leaderboard. Balances flush every 60 seconds.

- [x] `PointsService` interface: `get`, `set`, `add`, `take`, `top`
- [x] YAML-backed store (`points.yml`), clamp at zero
- [x] Register the service in `ServicesManager` so other plugins can load it
- [x] Async save every minute (60s) + save on disable
- [x] `/points` command (self balance, staff can inspect others, `/points top` leaderboard)
- [x] Vault `Economy` bridge over the ledger (offline refunds, integer points)
- [x] Sidebar balance, top-3 leaderboard, online count and tab-list header
- [ ] Shared cooldown helper and message helpers
- [ ] Decide if core also owns the parkour scoreboard

## guab-blocks — `░░░░░░░░░░` 0% (0/10)

**What it does:** the parkour's prank blocks. Staff tag blocks; stepping on one can drop the player through the floor, hit them with a random bad potion effect, or swap them with another player.

Status: boilerplate. Nothing implemented.

- [ ] `blocks.yml` store keyed by `world;x;y;z`
- [ ] `/guabblocks add <fake|potion|swap>`, `remove`, `list`, `reload`
- [ ] Tab completion + permission
- [ ] Fake block: fall-through chance, restore delay, break sound
- [ ] Potion block: random effect from a list, duration and amplifier
- [ ] Swap block: teleport victim to a random player, cooldown
- [ ] Trigger on stepping (block under feet, change-of-block only)
- [ ] Per-player and per-block cooldowns
- [ ] Config for chances/durations; restore fake blocks after a show
- [ ] Make sure blocks do not fire twice while standing still

## guab-items — `░░░░░░░░░░` 0% (0/7)

**What it does:** the prank items players earn or gamble for. A wind charge that boosts you forward or knocks others off, and a feather that grants permanent jump boost.

Status: boilerplate. Nothing implemented.

- [ ] PDC-tagged items so names cannot be faked
- [ ] Wind charge item: boost self forward, knock nearby players away
- [ ] Jump boost feather: permanent jump boost, consumed on use
- [ ] `/guabitems give <player> <wind_charge|jump_boost> [amount]`
- [ ] Right-click handling for main and off hand
- [ ] Cooldowns and config values
- [ ] Block crafting/renaming from producing fake copies

## guab-rickroll — `████████░░` 79% (15/19)

**What it does:** the 5% joke. A tagged block sends the victim to a hidden cinema, locks them in spectator, and plays the rickroll video and song, then puts them back exactly where they were.

Status: live on build `cc5b645`. One-minute cut from 0:19 (720 frames, 60s, 7.62 MB pack), 12 fps, shared 256-color palette, synced lyrics, notched 1:00 boss bar, brighter cinema, disconnect restore, and 5%/5 min trigger.

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
- [ ] Mark the first block in-game with `/rickroll setblock`
- [ ] Full 1-minute show test, including disconnect mid-show
- [x] Tune `cinema.width`/`height`, `chance`, `cooldown-seconds` (20/11.25, 5%, 5 min)
- [x] Decide full video vs loop: 1-minute cut, no loop
- [ ] Optional: VHS static, scanlines

## guab-gambling — `████████░░` 77% (10/13)

**What it does:** the point sink. A walk-in casino for the one-night event: MineGames runs the physical tables (roulette, slots, mines, chicken, mob fights, dice) on top of the Guab points ledger, inside a venue built by this plugin.

Status: live in production. Venue built, all six station types placed, event open, points flowing through Vault. Bets are uncapped, roulette idles until the first wager, fights preview frozen fighters with their real gear, holograms no longer duplicate, and the slots are retuned to roughly 92% RTP. Chicken still needs its paytable fixed.

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
- [ ] Retune chicken: `green-bonus: 8.0` is +EV, early cash-out is risk-free, offline wins are not paid
- [ ] Rehearse with two accounts, including a disconnect mid-round refund
- [ ] Teardown: remove stations, close the casino

Event-night ops: freeze the auto-deploy timer, snapshot `points.yml`, and close with `event.enabled: false` + `/casino reload`.

## guab-minigames — `░░░░░░░░░░` 0% (0/7)

**What it does:** the point faucet. Battle pit and other small games outside the main parkour award points for kills and wins, which then feed gambling and item rewards.

Status: blocked on the core points ledger.

- [ ] Battle pit arena spawn (`/battlepit setspawn`)
- [ ] `/battlepit join` / `leave`, teleport in and out
- [ ] Track who is in the pit
- [ ] Kill reward and death penalty via `PointsService`
- [ ] Respawn at the pit spawn, keep or drop inventory config
- [ ] Action bar/scoreboard for the current streak
- [ ] Additional point-earning minigames outside the main parkour

## Platform

Decision (2026-11-05): stay on Paper. Pumpkin (Rust) was reviewed — it is pre-1.0, plugins are
WASM, and rickroll's features map reasonably well, but the API is unstable and the world/render
parity risk is not worth a cutover yet. Revisit when Pumpkin reaches 1.0, or run a parallel
PatchBukkit spike on a spare port if curiosity wins.
