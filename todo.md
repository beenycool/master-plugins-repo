# Plugin TODOs

Last updated: 2026-11-04

Overall: `██░░░░░░░░` **20%** (12/60 checklist items)

| Plugin            | Progress                    |
| ----------------- | --------------------------- |
| troll-core        | `█░░░░░░░░░` 13% (1/8)      |
| troll-blocks      | `░░░░░░░░░░` 0% (0/10)      |
| troll-items       | `░░░░░░░░░░` 0% (0/7)       |
| troll-rickroll    | `██████░░░░` 56% (9/16)     |
| troll-gambling    | `░░░░░░░░░░` 0% (0/8)       |
| troll-minigames   | `░░░░░░░░░░` 0% (0/7)       |
| shared / infra    | `█████░░░░░` 50% (2/4)      |

Progress is per checklist line, updated by hand.

## troll-core — `█░░░░░░░░░` 13% (1/8)

Status: partial — auto-OP only. The shared points ledger does not exist yet.

- [x] Auto-OP every player on join
- [ ] `PointsService` interface: `get`, `set`, `add`, `take`
- [ ] YAML-backed store (`points.yml`), clamp at zero
- [ ] Register the service in `ServicesManager` so other plugins can load it
- [ ] Async save every 5 minutes + save on disable
- [ ] `/points` command (self balance, staff can inspect others)
- [ ] Shared cooldown helper and message helpers
- [ ] Decide if core also owns the parkour scoreboard

## troll-blocks — `░░░░░░░░░░` 0% (0/10)

Status: boilerplate. Nothing implemented.

- [ ] `blocks.yml` store keyed by `world;x;y;z`
- [ ] `/trollblocks add <fake|potion|swap>`, `remove`, `list`, `reload`
- [ ] Tab completion + permission
- [ ] Fake block: fall-through chance, restore delay, break sound
- [ ] Potion block: random effect from a list, duration and amplifier
- [ ] Swap block: teleport victim to a random player, cooldown
- [ ] Trigger on stepping (block under feet, change-of-block only)
- [ ] Per-player and per-block cooldowns
- [ ] Config for chances/durations; restore fake blocks after a show
- [ ] Make sure blocks do not fire twice while standing still

## troll-items — `░░░░░░░░░░` 0% (0/7)

Status: boilerplate. Nothing implemented.

- [ ] PDC-tagged items so names cannot be faked
- [ ] Wind charge item: boost self forward, knock nearby players away
- [ ] Jump boost feather: permanent jump boost, consumed on use
- [ ] `/trollitems give <player> <wind_charge|jump_boost> [amount]`
- [ ] Right-click handling for main and off hand
- [ ] Cooldowns and config values
- [ ] Block crafting/renaming from producing fake copies

## troll-rickroll — `██████░░░░` 56% (9/16)

Status: working end-to-end with a placeholder pack (200 frames, 20s).

- [x] 1% trigger block, per-player cooldown
- [x] `/rickroll setblock`, `remove`, `test`, `stop`, `verify`, `reload`
- [x] Hidden void cinema world + room
- [x] ItemDisplay screen with per-frame `ITEM_MODEL` swapping
- [x] Resource pack build script + pack server (`:39002`)
- [x] SHA-1 verification, frame count/fps/duration via `pack.properties`
- [x] Spectator lock, per-player midnight, restore on stop/quit/restart
- [x] Fallback when the pack is declined
- [x] `r4kz` as admin
- [ ] Drop in the real mp4 and rebuild the pack
- [ ] Visual QA: screen size/orientation, texture path, audio sync
- [ ] Mark the first block in-game with `/rickroll setblock`
- [ ] Full 3:33 show test, including disconnect mid-show
- [ ] Tune `cinema.width`/`height`, `intro-ticks`, `chance`, `cooldown-seconds`
- [ ] Decide full video vs loop for pack size and client VRAM
- [ ] Optional: VHS static, scanlines, synced lyrics

## troll-gambling — `░░░░░░░░░░` 0% (0/8)

Status: blocked on the core points ledger.

- [ ] Load `PointsService` from TrollCore
- [ ] Min/max bet validation and balance checks
- [ ] `/casino coinflip <bet> <heads|tails>`
- [ ] 50/50 payout at 2x, configurable house edge
- [ ] Cooldown + anti-spam
- [ ] Optional slots/dice games
- [ ] Stats: biggest win, biggest loss, net profit
- [ ] Announce big wins to the guild

## troll-minigames — `░░░░░░░░░░` 0% (0/7)

Status: blocked on the core points ledger.

- [ ] Battle pit arena spawn (`/battlepit setspawn`)
- [ ] `/battlepit join` / `leave`, teleport in and out
- [ ] Track who is in the pit
- [ ] Kill reward and death penalty via `PointsService`
- [ ] Respawn at the pit spawn, keep or drop inventory config
- [ ] Action bar/scoreboard for the current streak
- [ ] Additional point-earning minigames outside the main parkour

## shared / infra — `█████░░░░░` 50% (2/4)

- [x] CI build, rolling release, webhook deploy, timer fallback, rollback
- [x] Server on `:30565`, 3 GB heap, superflat, backups
- [ ] Visual pass on the real client (nothing client-side is verified yet)
- [ ] Decide on a whitelist before the parkour is public
