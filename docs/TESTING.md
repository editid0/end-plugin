# Testing

Endfold is tested at three levels. Everything below was actually run; nothing is listed as tested that was only
reviewed. Raw outputs are in [`docs/test-results/`](test-results/).

| Level | How to run | Result (2026-11-08) |
|---|---|---|
| Unit tests (JUnit 6, real SQLite files) | `./mvnw clean package` | **123 passed**, 0 failed, 0 skipped |
| Real-server integration (Purpur 26.3 build 2657, two phases across a full restart) | `./testkit/run-integration.sh` | **76 checks passed** (51 + 25), 0 failed, 0 skipped |
| Milestone-1 feasibility proof of concept (Purpur 26.3) | scratch plugin, see ARCHITECTURE.md | all questions answered positively |
| Independent code review (security, duplication, threading) | review agent | 17 findings (no critical/high); all medium findings fixed, see below |

## 1. Unit tests (`src/test`)

Services are Bukkit-free, so they are tested against a real SQLite database file per test (no mocks for persistence).
A fake clock drives expiry; `Fixture.restart()` closes and reopens the database to simulate a server restart.

* **Migrations** - fresh database, idempotent re-run, checksum tampering refused, newer-schema database refused,
  failed migration rolls back, foreign keys enforced.
* **Spaces** - SOLO/GROUP creation, SOLO uniqueness under 16 concurrent creates, ownership and membership limits,
  name validation (length, MiniMessage tags, section signs), deterministic unique world names, auto-select of the
  first space only, permission checks for rename/visibility, SOLO cannot be public, disband archives without deleting
  any records, member limit bounds.
* **Membership** - invitation permissions and edge cases, expiry, double accept from two threads (one membership),
  full spaces, decline/revoke, role hierarchy for kicks, owner-only role changes, ownership transfer (exactly one
  OWNER row), leave policies (REQUIRE_TRANSFER / AUTO_TRANSFER / sole owner must disband), last-slot races,
  public OPEN vs APPROVAL joining, requests approve/deny/cancel, approval into a full space.
* **Party switching** (`PartySwitchingTest`) - the exact Alice/Bob scenario from the specification; memberships,
  Bob's selection, per-space return locations and progress rows are unchanged throughout.
* **Profiles** - select requires membership of an ACTIVE space; stale selections repaired on join; logout sessions.
* **Loot** - `createOnce` under concurrency stores exactly one generation and every caller sees the same bytes;
  players are independent; optimistic revisions; claims survive a database restart.
* **Elytra** - one claim per (space, player, ship) also under concurrency; per-space/global limits and cooldown;
  claims survive restart; a claim committed without delivery stays in the outbox for redelivery; delivery marking is
  idempotent.
* **Progress** - dragon kills update shared and individual progress once; achievements granted once; late joiners get
  nothing for fights they missed; disabled achievements are not granted; veteran after 5 fights.
* **World files** (`WorldRepositoryTest`) - 26.x level folder resolution, backup/restore round trip (previous state
  kept in trash, `session.lock` excluded), zip-slip entries rejected without touching the world, backup names
  validated, orphan folders reported but never touched.
* **Review regressions** (`ReviewFixesTest`) - invitations from kicked or demoted inviters are unusable; a DELETED
  space can never be flipped back to another status.

## 2. Real-server integration tests (`testkit/`)

No Minecraft client can connect to 26.3 headlessly (mineflayer / node-minecraft-protocol support up to 26.1), so the
test kit creates **server-side fake players**: real `ServerPlayer` objects joined through the server's own
`PlayerList` with an in-memory network channel. They walk into real portal blocks, are seen by the real dragon fight,
load chunks and trigger real Bukkit events. Gameplay actions a client would send (opening a chest, clicking an item
frame, clicking a menu slot) are fired as the corresponding Bukkit events into the real listeners.

`run-integration.sh` builds both jars, starts a throw-away Purpur server, runs **phase 1**, stops the server, starts it
again and runs **phase 2**. The only configuration override is a 36-second shulker cooldown (instead of 72 hours) so a
respawn can be observed.

### Phase 1 (51 checks)

* **Multiple worlds** - first End portal entry creates Alice's personal End and routes her onto a rebuilt obsidian
  platform; Bob creates a group and enters it; both worlds are `THE_END`, distinct, have their own `DragonBattle` and
  different random seeds; block changes are independent.
* **Party switching** - Alice joins Bob's group (invitation), belongs to two spaces, selects Bob's (does not teleport
  her - select is not enter), Bob's selection is unaffected; Alice returns to the exact overworld spot next to the
  portal (not inside it), re-enters through the portal into Bob's End; later switches back to her solo End, whose
  blocks are intact, while still being a member of Bob's group.
* **Access control** - command and plugin teleports of a non-member (Carol) into Bob's End are cancelled; the router
  refuses her; when Carol uses the portal she gets her own new End, never Bob's.
* **Dragon** - the dragon spawns in Bob's End; Alice and Bob damage it; it dies; a gateway spawns; shared progress
  records one kill; both fighters are credited with participation; Alice's solo dragon and progress are untouched.
* **Gateway** - Bob enters the new gateway and stays inside his own EndSpace (outer islands).
* **Exit portal** - Alice uses the exit portal and returns to the entry point stored for Bob's space.
* **Entities** - an item thrown into the overworld End portal reaches no End world; a pig in an EndSpace exit portal
  stays in that EndSpace.
* **Personal loot** - an End City chest is registered with its vanilla loot table removed and an empty physical
  inventory; Bob and Alice open the same chest and see different personal loot; Bob takes an item, reopens and sees
  his changed contents (no re-roll); breaking the chest is cancelled; a hopper below it extracts nothing.
* **Shulkers** - a tracked End City shulker is killed and respawns at the same spawn point after the cooldown.
* **GUI exploit resistance** - with the real main menu open, pickup, shift-click, number-key swap, offhand swap,
  double-click collect, shift-click from the player inventory, drop and drag are all cancelled; menu and player items
  unchanged.
* **Performance sample** - 5 additional EndSpace worlds generated in ~190 ms total (~38 ms each) on an Apple-silicon
  laptop, ~140 MB heap delta for 8 loaded End worlds, TPS 20.0 (informational, single sample).
* **Lifecycle** - an empty EndSpace world unloads and reloads with its blocks intact.
* **Elytra** - Bob claims an Elytra from a real End Ship frame; the frame keeps displaying it and is fixed and
  invulnerable; a repeated claim gives nothing; Alice independently claims her own from the same ship.
* Bob logs out while inside his EndSpace.

### Phase 2 after a full restart (25 checks)

* Spaces, memberships, roles and both players' selections persisted; worlds are not loaded until needed.
* Both worlds load with their blocks; the group's dragon stays defeated, the solo dragon untouched; gateways persisted;
  per-space return locations persisted.
* Bob, who logged out inside his (now unloaded) EndSpace, is restored to his exact logout position.
* Bob's personal chest has the same contents as after his phase-1 change (no re-roll); the taken item is still in his
  inventory; he still has exactly one Elytra; the claim persisted and the outbox is empty.
* **Admin tools** - backup creates a zip; a block changed after the backup is reverted by restore (world evicted,
  unloaded, previous folder kept in trash); delete removes the space from the directory, moves its folder out of the
  level into `plugins/Endfold/trash`, nobody can enter it any more, other spaces are untouched.

### Bugs the integration tests found (all fixed)

1. **Critical:** since 26.1 the overworld lives in `world/dimensions/minecraft/overworld`; Endfold derived the level
   folder from it and looked for EndSpace folders in the wrong place, so an unloaded EndSpace was marked BROKEN on
   its next load (and start-up recovery would have done so for every space).
2. A second start-up on the same day failed because the database backup file name already existed (`VACUUM INTO`
   refuses to overwrite); backups are now timestamped, pruned, and never block start-up.
3. Composing a service call from a database-thread callback tripped an over-strict re-entrancy guard (automatic
   personal End creation failed).
4. The default shulker caps (6 per city / 20 per space) were below real vanilla city populations, so killed shulkers
   could never respawn; defaults are now 12 / 50 and the semantics are documented.

## 3. Review findings and fixes

An independent review (no critical/high) reported, among others: invitations staying valid after the inviter lost
their rights; admin delete/restore racing with members re-entering; a deleted space being re-marked BROKEN by a late
load; a fast close-and-reopen of a personal chest reading contents before the last save; joining players checked only
after a database round trip; backups that could contain a truncated region file; loot/Elytra paths not checking
membership; ambiguous admin name resolution; forced unloads hitting freshly loaded worlds; a broad teleport
authorisation window; cursor items deleted by menus; missing permission re-checks in admin confirmations. All of these
were fixed (maintenance lock, revocation and re-validation of invitations, synchronous join check, all-or-nothing
backups, per-target authorisation, and so on) and the service-level ones have regression tests.

Remaining low-severity items are documented in [LIMITATIONS.md](LIMITATIONS.md).

## 4. Not verified automatically

Be aware of what these tests do **not** prove:

* **Real clients.** Rendering, client-side prediction, real mouse input in menus and the Paper dialog window (name
  input) were not exercised with a real game client. Menu logic was exercised through Bukkit events on a real server.
* **Hard crashes.** Crash safety of reward delivery is covered by unit tests that simulate the crash point (claim
  committed, delivery not marked) and by restart tests; the server was not `kill -9`ed mid-transaction.
* **Pistons and explosions** against protected chests, hopper minecarts, and chorus fruit / ender pearls *into*
  another instance (pearls within an instance and pearl events were verified in the proof of concept).
* **Optional dragon health scaling** (off by default) and achievement chat announcements.
* **Paper** (as opposed to Purpur). Endfold uses only Paper API, but the automated runs used Purpur 26.3 build 2657.
* **Scale.** Only 8 simultaneously loaded EndSpace worlds were measured.

## Running the tests yourself

```bash
./mvnw clean package                 # unit tests + plugin jar
./testkit/run-integration.sh         # downloads Purpur 26.3 build 2657, runs both phases (~10 minutes)
./testkit/run-integration.sh /path/to/server.jar   # use your own Paper/Purpur 26.3 jar
```

Requirements: JDK 25+, `curl`, `timeout` (coreutils). The integration server runs in `target/it-server` with
`online-mode=false` on port 25601 - never point it at a production server directory. The test kit plugin must never
be installed on a real server.
