# Known limitations and design trade-offs

These are deliberate decisions or limits of the server API. They are listed so administrators know exactly what
Endfold does and does not guarantee.

## Worlds and portals

* **Vanilla routes End portals to `minecraft:the_end`.** Endfold cancels the portal touch (`EntityPortalEnterEvent`)
  and performs the teleport itself, so the shared End must not be relied upon by other plugins for End progression.
  Plugins that teleport players into `world_the_end` are blocked while `portals.block-unmanaged-end-access` is
  true (bypass: `endfold.admin.teleport`).
* **Arrival platform.** Vanilla only builds the obsidian platform in the shared End, so Endfold rebuilds a
  vanilla-shaped 5x5 platform at (100, 48, 0) on every arrival - exactly like vanilla does.
* **End credits** are never shown when leaving an EndSpace (vanilla only shows them in `minecraft:the_end`). Players'
  `seenCredits` flag is untouched. A vanilla client therefore does not show the poem after the first dragon kill.
* **World load stall.** World creation is synchronous in the server API (no async `createWorld`). Generating a
  brand-new EndSpace typically takes 20-60 ms on the test machine (see TESTING.md); loading an existing one is
  faster. Loads are deferred to a scheduler tick and deduplicated, and `max-loaded` caps memory use.
* **Logout inside an unloaded EndSpace.** After a restart a player who logged out inside an EndSpace first appears in
  the main world (the server cannot place them into an unloaded world), and Endfold then loads the EndSpace and
  moves them back to their logout position within a few ticks.
* **Other plugins that call teleport APIs** are subject to the same membership check through `PlayerTeleportEvent`.
  A plugin that moves players without firing a teleport event (e.g. raw NMS) bypasses the event; the 5-second
  access sweep then evicts unauthorised players.
* **Non-player entities** cannot use End portals in or out of EndSpaces (`entity-policy: BLOCK` also blocks gateways
  for mobs/items; `VANILLA_LOCAL` allows gateways, which always stay inside the same world). Items thrown into an
  overworld End portal stay in the portal instead of travelling to the shared End.
* **Shared End data** (`world_the_end`) is never deleted or modified by Endfold; it is simply not used.

## Loot and rewards

* **Personal chests are registered when their chunk first loads** in an EndSpace, while vanilla's loot table is still
  unrolled. A chest whose loot table was already consumed before Endfold saw it (e.g. world copied in from elsewhere)
  cannot become a personal chest.
* **Residual duplication window.** The personal chest is saved one tick after every change (and immediately on close,
  quit and shutdown), followed by a throttled player-data save. If the server crashes in the instant after vanilla
  autosaved the player file but before the chest save committed, an item taken from the chest could exist in both.
  The window is a single tick; SQLite `synchronous=FULL` makes the chest save itself durable.
* **Elytra ship detection** uses the End City structure piece bounding boxes from the API (ship-sized piece,
  13x24x29 in either rotation). Player-placed item frames are tagged on placement and are never eligible.
* **Creative middle-click cloning** of items in personal chests is not blocked (creative players can create items
  anyway). `/clone` and `/data` on registered chests are not intercepted (operator commands).
* **Shulkers**: spawn points are learned from the shulkers that exist when a city's chunks first load in an
  EndSpace; a city whose shulkers were killed before Endfold was installed cannot be re-populated.

## Dragon

* Fight tracking (damage, presence) is kept in memory. If the server restarts mid-fight the dragon is picked up
  again but participation tracking restarts from zero, and optional health scaling is not re-applied.
* Explosions from beds/respawn anchors are credited to the nearest player within 24 blocks.

## Membership

* An owner cannot leave an **archived** group that still has other members (transfer and disband require an active
  space). Archived spaces are inaccessible anyway; staff can re-activate, transfer (`/endfold transfer`) or delete it.
* Space names are not unique. Commands accept names only when they match exactly one space; ids always work.

## GUI

* Inventory menus cannot accept free text; names are entered through Paper's dialog window (default) or chat
  (`gui.text-input`). Closing a dialog with Escape sends no response to the server, so no menu is reopened.
* Restoring a backup is a command (`/endfold restore`); the admin GUI offers backup, archive, activate, delete and
  repair but has no backup-file picker.
* Menu titles are fixed while a menu is open; the current page number is shown on the navigation item.

## Testing

* Integration tests use server-side fake players (real `ServerPlayer` objects, no network client). Client-only
  behaviour - rendering, client prediction, actual mouse clicks in menus - is not covered by automated tests; menu
  click handling is exercised through Bukkit events. See TESTING.md for exactly what was and was not verified.
