# Endfold architecture

Endfold replaces the single shared End with **EndSpaces**: independent, persistent End dimensions. A SOLO EndSpace
is simply a GROUP EndSpace limited to one member - both use exactly the same code.

## Feasibility (Milestone 1, verified on Purpur 26.3 build 2657)

The architecture rests on facts verified with a proof of concept on the real server (see [TESTING.md](TESTING.md)):

| Question | Finding |
|---|---|
| Can several `THE_END` worlds be loaded at once? | Yes. `WorldCreator.ofKey(endspace:<id>)` worlds load in ~20-50 ms once generated. |
| Does each get its own dragon fight? | Yes. In 26.3 `ServerLevel` creates an `EnderDragonFight` when `DimensionType.hasEnderDragonFight()` is true and stores it as per-level saved data (`<world>/data/minecraft/ender_dragon_fight.dat`). Two fresh worlds spawned two dragons simultaneously; killing one left the other untouched. |
| Gateways? | Spawn per world after a kill; a player entering one stays in the same world (`TeleportCause.END_GATEWAY`). |
| Portals? | Vanilla hard-codes the destination to `minecraft:the_end` and builds the obsidian platform there *before* any event. Endfold therefore cancels `EntityPortalEnterEvent` and teleports players itself, rebuilding the platform in the EndSpace. |
| Credits / exit portal? | Credits are keyed on the `minecraft:the_end` dimension key, so custom End worlds skip them. Exit portals are also intercepted. |
| Persistence? | Blocks, dragon state, gateway count and exit portal survive unload/reload and full restarts. |

Limitations found are listed in [LIMITATIONS.md](LIMITATIONS.md).

## Layers

```
 commands (/end, /endfold)        GUI (dev.endfold.gui)           listeners (portal, access, loot, dragon)
            \                         |                                 /
             +-------------- Actions (dev.endfold.action) -------------+     <- shared player-facing operations
                                      |
                      Services (dev.endfold.service)  - pure Java, no Bukkit types, unit tested
                      SpaceService, MembershipService, ProfileService, ProgressService,
                      LootService, ElytraService, ShulkerService, AuditService
                                      |
                      Repositories (dev.endfold.repo interfaces, dev.endfold.db.sql implementations)
                                      |
                      Database (single DB thread, SQLite WAL, migrations)

 World layer (dev.endfold.world): InstanceManager, WorldLifecycleManager, WorldGenerator, WorldRepository
 Routing (dev.endfold.portal):   PortalRouter, PortalListener, AccessGuard, SessionListener
```

* **Business logic lives in services.** Every mutation runs in one database transaction that re-validates
  permissions and limits, so a stale GUI or a race between two players cannot bypass rules.
* **GUIs and commands share `Actions`.** They call the same service method, then the same feedback, notification and
  eviction code on the server thread.
* **Listeners are thin.** They translate events into calls on the router, the services or a gameplay module.

## Threading

* All JDBC work runs on one dedicated thread (`Endfold-DB`). SQLite serialises writers anyway, so one connection
  avoids `SQLITE_BUSY`, and no connection pool or ORM is needed. A PostgreSQL backend would plug in a pooled
  `Database` + its own `Repositories` without touching services.
* Services return `CompletableFuture<Result<T>>`. Code that touches Bukkit hops back with `Sync.main()`.
* `SpaceDirectory` is a thread-safe, write-through, in-memory copy of spaces, memberships and selections. It is
  updated from after-commit hooks and read synchronously by event handlers (portal and teleport events must decide
  immediately). Services always re-check the database inside their transaction.
* Worlds are created and loaded only on the server thread, deferred to a scheduler tick (never inside an event).
  File work (backups, restores, trash) runs on an I/O pool.

## Data model

Identifiers are UUIDs. World names are derived from the space id: `endspace_<first 8 hex>`; the world key is
`endspace:<8 hex>`, stored at `<level>/dimensions/endspace/<8 hex>/`. Names are never derived from player names.

Tables (see `src/main/resources/db/migrations/V1__init.sql`):

| Table | Purpose |
|---|---|
| `end_spaces` | id, type, name, owner, world name/uuid, seed, status (ACTIVE / ARCHIVED / BROKEN / DELETED), max members, member count |
| `end_space_members` | (space, player) -> role OWNER/ADMIN/MEMBER. Partial unique index: exactly one OWNER per space |
| `end_player_profiles` | player -> selected space (portal destination), last name |
| `end_invitations` | persistent invitations with expiry; partial unique index: one PENDING per (space, invitee) |
| `end_public_listings` | row exists = space is listed; join mode OPEN / APPROVAL |
| `end_membership_requests` | approval-required applications |
| `end_space_progress` | shared: dragon defeated, kills, gateways |
| `end_player_progress`, `end_player_achievements` | individual progress per (space, player) and achievements |
| `end_dragon_fights`, `end_dragon_fight_participants` | fight history, damage and participation |
| `end_loot_containers` | End City chests registered while their vanilla loot table was still unrolled |
| `end_loot_claims`, `end_personal_inventories` | once-only claims; personal chest contents with optimistic revision |
| `end_elytra_claims`, `end_pending_deliveries` | per-ship claims and the delivery outbox |
| `end_return_locations`, `end_player_sessions` | per-(player, space) overworld return point; logout position inside a space |
| `end_shulker_spawns` | End City shulker spawn points and their respawn schedule |
| `end_audit_log` | administrative and lifecycle audit trail |
| `schema_version` | applied migrations with SHA-256 checksums |

### Select vs enter vs leave

* **Select** (`ProfileService.select`) changes only the player's own `selected_space_id`. No teleport, no membership change.
* **Enter** (`PortalRouter.enter`) teleports now; it never changes the selection or membership.
* **Return** (`PortalRouter.exit`) goes back to the overworld point stored for that (player, space).
* **Leave** (`MembershipService.leave`) removes the membership. Owners must transfer first (or ownership passes
  automatically, depending on config); a sole owner must disband, which archives - never deletes - the world.

Nothing in switching ever deletes a space, removes another membership, resets progress or claims, transfers
ownership or changes another player's selection. This is covered by `PartySwitchingTest` and the integration tests.

### Migrations and crash safety

* `MigrationRunner` applies `db/migrations/V<n>__*.sql` listed in `index.txt`, each in its own transaction, recording
  a checksum. It refuses to start if an applied script changed or the database is newer than the plugin. The database
  is copied (`VACUUM INTO`) at start-up and daily before anything else touches it.
* `PRAGMA synchronous=FULL`: a committed claim is on disk before the reward is handed out.
* **Elytra**: claim row + outbox row are committed together, then the item (tagged with its delivery id) is given and
  the outbox row marked delivered. A crash in between leads to redelivery on next join, and the tag lets redelivery
  detect an item that was in fact delivered.
* **Personal chests**: loot is rolled on the server thread, then one transaction inserts the claim and the inventory.
  If the claim already existed the stored inventory is shown instead - loot can never be rolled twice. See
  `loot/` javadocs for the residual window between chest saves and vanilla's player-data saves.
* **Worlds**: a space whose generated world folder disappears is marked BROKEN and never silently regenerated.
  Deletion evicts, unloads, backs up, then moves the folder to `plugins/Endfold/trash/`.

## World lifecycle

1. A player needs a space -> `WorldLifecycleManager.ensureLoaded` (deduplicated per space, so concurrent requests can
   never create a world twice). First use generates the world with the space's seed.
2. While players are inside, the world stays loaded. When the last one leaves, an inactivity timer starts.
3. After `unload-after-minutes` the world is saved and unloaded (`Bukkit.unloadWorld(world, true)`); files and
   records stay. A global cap (`max-loaded`) unloads idle worlds early or refuses new loads while busy.
4. Start-up recovery checks every generated space has its folder (else BROKEN) and lists orphan folders.

## Portal routing summary

| Situation | Behaviour |
|---|---|
| Player touches End portal outside an EndSpace | cancel, route to selected EndSpace (create personal End on first use if enabled), store return point |
| Player touches exit portal inside an EndSpace | cancel, return to stored point -> respawn point -> fallback spawn |
| Player uses a gateway inside an EndSpace | vanilla (stays in the same world); any cross-world gateway result is cancelled |
| Non-player entity touches an End portal | cancelled (no leaks between instances or to the shared End) |
| Teleport into an EndSpace (command, plugin, pearl, chorus...) | allowed only for members of an ACTIVE space or `endfold.admin.teleport` |
| Player inside a space they lost access to | evicted (on change, on join, and by a 5-second sweep) |
| Respawn location inside an inaccessible space | replaced with the fallback |
| Shared vanilla End (`world_the_end`) | not reachable through portals or teleports when `block-unmanaged-end-access` is true |
