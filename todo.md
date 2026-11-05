# Plugin TODOs

Last updated: 2026-11-04

Overall: `███░░░░░░░` **25%** (14/57 checklist items)

| Plugin            | Progress                    |
| ----------------- | --------------------------- |
| guab-core        | `░░░░░░░░░░` 0% (0/7)       |
| guab-blocks      | `░░░░░░░░░░` 0% (0/10)      |
| guab-items       | `░░░░░░░░░░` 0% (0/7)       |
| guab-rickroll    | `████████░░` 78% (14/18)    |
| guab-gambling    | `░░░░░░░░░░` 0% (0/8)       |
| guab-minigames   | `░░░░░░░░░░` 0% (0/7)       |

Progress is per checklist line, updated by hand.

The loop: **minigames earn points → gambling risks them → items and blocks are the rewards.**

## guab-core — `░░░░░░░░░░` 0% (0/7)

**What it does:** the shared foundation. Holds the player points ledger (the parkour currency) and common helpers. Every other plugin depends on it.

Status: boilerplate. The shared points ledger does not exist yet.

- [ ] `PointsService` interface: `get`, `set`, `add`, `take`
- [ ] YAML-backed store (`points.yml`), clamp at zero
- [ ] Register the service in `ServicesManager` so other plugins can load it
- [ ] Async save every 5 minutes + save on disable
- [ ] `/points` command (self balance, staff can inspect others)
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

## guab-rickroll — `████████░░` 78% (14/18)

**What it does:** the 1% joke. A tagged block sends the victim to a hidden cinema, locks them in spectator, and plays the rickroll video and song, then puts them back exactly where they were.

Status: one-minute cut from 0:19 (720 frames, 60s, 7.6 MB pack), 12 fps, synced lyrics, notched 1:00 boss bar, brighter cinema, disconnect restore, and 5%/5 min trigger.

- [x] 1% trigger block, per-player cooldown
- [x] `/rickroll setblock`, `remove`, `test`, `stop`, `verify`, `reload`
- [x] Hidden void cinema world + room
- [x] ItemDisplay screen with per-frame `ITEM_MODEL` swapping
- [x] Resource pack build script + pack server (`:39002`)
- [x] SHA-1 verification, frame count/fps/duration via `pack.properties`
- [x] Spectator lock, per-player midnight, restore on stop/quit/restart
- [x] Fallback when the pack is declined
- [x] 60-second cut from 0:19 (AV1 source, 12 fps, 192x108, shared 256-color palette, 7.6 MB lossless)
- [x] Visual QA: video, texture path, audio on the old 6-block screen
- [x] Synced lyrics on the action bar (LRC timestamps)
- [x] Boss-bar countdown timer (1:00 to 0:00, notched)
- [ ] Check the new 10-block screen, red frame, lights, and stair direction
- [ ] Mark the first block in-game with `/rickroll setblock`
- [ ] Full 1-minute show test, including disconnect mid-show
- [x] Tune `cinema.width`/`height`, `chance`, `cooldown-seconds` (20/11.25, 5%, 5 min)
- [x] Decide full video vs loop: 1-minute cut, no loop
- [ ] Optional: VHS static, scanlines

## guab-gambling — `░░░░░░░░░░` 0% (0/8)

**What it does:** the point sink. Players wager the points they earn from minigames on casino games; a configurable house edge keeps the economy in check.

Status: blocked on the core points ledger.

- [ ] Load `PointsService` from GuabCore
- [ ] Min/max bet validation and balance checks
- [ ] `/casino coinflip <bet> <heads|tails>`
- [ ] 50/50 payout at 2x, configurable house edge
- [ ] Cooldown + anti-spam
- [ ] Optional slots/dice games
- [ ] Stats: biggest win, biggest loss, net profit
- [ ] Announce big wins to the guild

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
