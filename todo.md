# TODO

Last updated: 2026-11-04

## Done

- [x] Gradle multi-module scaffold: six Paper `26.3` plugins on Java 25
- [x] CI: push to `main` builds every jar, publishes the rolling `deploy-latest` release (retries GitHub 5xx)
- [x] Deploy: Actions pings the VM webhook (`:39001`), 2-minute timer as fallback, RCON restart warning
- [x] Server: Docker Paper 26.3 on `:30565`, 3 GB heap / 4 GB cap, superflat, 20 slots, healthy
- [x] Persistence: world + configs in `/opt/troll-server/data`, daily backups (last 7)
- [x] Rollback script and previous-build tracking
- [x] OP on join (`troll-core`)
- [x] Rickroll plugin: 1% block trigger, hidden cinema world and room, ItemDisplay screen, per-frame item models, spectator lock, per-player midnight, restore on stop/quit/restart
- [x] Rickroll pack pipeline: ffmpeg → frames + item models + ogg + zip + SHA-1, served on `:39002`
- [x] Placeholder pack built and verified server-side (200 frames, 20s, 4.1 MB, sound present)
- [x] `r4kz` added as rickroll admin
- [x] `/rickroll setblock`, `remove`, `test`, `stop`, `verify`, `reload`

## Next

- [ ] Put the real mp4 on the VM and rebuild the pack:
      `sudo tools/build-rickroll-pack.sh /opt/troll-server/media/rickroll.mp4`
      then `sudo docker exec troll-mc rcon-cli rickroll reload`
- [ ] Visual QA with a real client: screen size/orientation, texture path, audio sync, pack prompt
- [ ] Mark the first 1% block in-game with `/rickroll setblock` looking at a block
- [ ] Test a full show: trigger, spectator lock, 3:33 duration, restore, disconnect mid-show
- [ ] Pick full video vs loop; tune fps/width for pack size and client VRAM (2000+ frames is a big item atlas)
- [ ] Tune `cinema.width` / `cinema.height`, `intro-ticks`, `chance`, `cooldown-seconds` after the first look
- [ ] Confirm `/rickroll stop` works in-game as r4kz
- [ ] Optional: VHS static sound, scanlines, synced lyrics
- [ ] Decide whether the server should get a whitelist before the parkour goes public

## Notes

- Media is never committed: the repo has the generator + placeholder only.
- Client rendering can't be verified headlessly (online-mode blocks bots), so the last pass needs a human join.
- Ports: `30565` game, `39001` deploy webhook, `39002` resource packs.
- Everyone is OP, so rickroll admin commands use the `admins` name list instead of permissions.
- Crafty and its servers on 25565/25566 are untouched.
