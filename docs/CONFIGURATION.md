# Configuration

Endfold writes three files to `plugins/Endfold/`:

| File | Contents |
|---|---|
| `config.yml` | Mechanics (documented inline; summary below). |
| `messages.yml` | Every chat message, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format. |
| `gui.yml` | Menu titles, item names, lore and icon materials. |

`/endfold reload` re-reads all three. Settings marked **restart** are read once at start-up.

**Validation.** Every value is type- and range-checked. An invalid value is reported in the console (and the reload
command prints the number of problems) and replaced with its documented default - an invalid entry never silently
disables a system. Missing message keys fall back to the bundled defaults.

**Automatic updates.** Each file has a `config-version`. When a new Endfold release ships a higher version, the
plugin adds the new settings (with their comments) to your existing file on start-up, keeps every value you changed,
and saves the previous file as `<name>.v<old>.bak`. Removed or custom keys are left alone. A file with a higher
version than the plugin (downgrade) or one that does not parse is never touched. Do not edit `config-version` yourself.
Developers: bump `config-version` in the bundled file whenever you add keys to it.

## `config.yml` summary

| Path | Default | Meaning |
|---|---|---|
| `instances.solo.enabled` | `true` | Allow personal EndSpaces. |
| `instances.solo.create-on-first-entry` | `true` | Create a personal End automatically when a player without a selection enters an End portal. |
| `instances.solo.default-name` | `"{player}'s End"` | Name of automatically created personal Ends. |
| `instances.group.enabled` | `true` | Allow group EndSpaces. |
| `instances.group.max-members` | `10` | Default member limit for new groups. |
| `instances.group.hard-max-members` | `50` | Upper bound owners may set. |
| `instances.group.max-owned-per-player` | `2` | Groups one player may own. |
| `instances.group.members-can-invite` | `false` | Let ordinary members invite. |
| `instances.group.owner-leave-policy` | `REQUIRE_TRANSFER` | Or `AUTO_TRANSFER` (to longest-serving admin, else member). |
| `instances.max-memberships-per-player` | `25` | All EndSpaces a player may belong to. |
| `instances.names.min-length` / `max-length` | `3` / `24` | EndSpace name length. |
| `instances.invitations.expire-after-hours` | `72` | Invitation lifetime. |
| `instances.invitations.max-pending-per-space` | `20` | Anti-spam limit. |
| `instances.worlds.seed-mode` | `RANDOM` | `RANDOM`, `FIXED` (`fixed-seed`) or `OVERWORLD`. |
| `instances.worlds.unload-after-minutes` | `10` | Idle time before an empty world is saved and unloaded. |
| `instances.worlds.max-loaded` | `30` | Concurrently loaded EndSpace worlds (0 = unlimited). |
| `instances.worlds.create-cooldown-seconds` | `30` | Minimum time between two group creations by the same player (staff exempt). |
| `instances.worlds.auto-backup` | `true` | Back up before admin delete/restore. |
| `instances.worlds.backups-to-keep` | `5` | Per EndSpace. |
| `instances.worlds.database-backups` | `true` | Copy the database at start-up and daily (`plugins/Endfold/backups/database/`). |
| `progression.dragon.repeatable` | `true` | Allow the vanilla four-crystal respawn in EndSpaces. |
| `progression.dragon.health-scaling` | `false` | Scale dragon health by **active participants** (never by member count). |
| `progression.dragon.health-scaling-per-player` | `0.25` | Extra health per participant beyond the first. |
| `progression.dragon.health-scaling-max-multiplier` | `3.0` | Cap. |
| `progression.dragon.participation-min-damage` | `10.0` | Damage needed to count as a participant ... |
| `progression.dragon.participation-min-seconds` | `60` | ... or time spent in the arena. |
| `progression.dragon.participation-radius` | `160` | Arena radius around the main island. |
| `progression.loot.personal-chests` | `true` | Personal End City chest loot. |
| `progression.loot.protect-containers` | `true` | Protect registered End City chests from breaking, hoppers, pistons, explosions. |
| `progression.achievements.disabled` | `[]` | Achievement keys to disable (see GUI.md). |
| `resources.elytra.enabled` | `true` | Personal Elytra claims. |
| `resources.elytra.per-space-limit` | `0` | Max claims per player per EndSpace (0 = unlimited). |
| `resources.elytra.global-limit` | `0` | Max claims per player across all EndSpaces (0 = off). |
| `resources.elytra.cooldown-hours` | `0` | Minimum time between two claims (0 = off). |
| `resources.shulkers.respawn` | `true` | Renewable End City shulkers. |
| `resources.shulkers.cooldown-hours` | `72` | Per spawn point. |
| `resources.shulkers.max-population` | `50` | Respawning pauses while this many tracked shulkers are alive in loaded parts of the EndSpace (0 = no cap). Respawns only refill original city spawn points. |
| `resources.shulkers.max-per-city` | `12` | Same, per End City. Keep at or above vanilla city sizes (~4-12). |
| `resources.shulkers.activation-radius` | `48` | A member must be this close for a respawn. |
| `resources.shulkers.check-interval-seconds` | `60` | Respawn check interval. |
| `portals.route-to-selected-space` | `true` | Route End portals into EndSpaces. If false, vanilla behaviour (not recommended). |
| `portals.return-to-entry-location` | `true` | Return to the stored entry point; otherwise to bed/anchor or spawn. |
| `portals.block-unmanaged-end-access` | `true` | Keep players and entities out of the shared `world_the_end`. |
| `portals.fallback-world` | `""` | World whose spawn is the last-resort return point (blank = main world). |
| `portals.entity-policy` | `BLOCK` | `BLOCK` or `VANILLA_LOCAL` (allow gateways for mobs/items within an EndSpace). |
| `portals.cooldown-seconds` | `3` | Between two portal routings for the same player. |
| `portals.direct-teleport.mode` | `AFTER_PORTAL` | Teleporting into an EndSpace from the menu or `/end enter` (no portal): `ALWAYS`, `AFTER_PORTAL` (only after the player has entered an End through a real End portal once - i.e. found a stronghold) or `NEVER` (portals only). Leaving is always allowed. Bypass: `endfold.bypass.direct-teleport`. |
| `portals.direct-teleport.count-vanilla-advancement` | `true` | With `AFTER_PORTAL`, players who already have the vanilla "The End?" advancement count as unlocked (useful when installing on an existing server). |
| `gui.enabled` | `true` | If false, `/end` sub-commands print text instead of opening menus. |
| `gui.use-access-item` | `false` | Give an item that opens the menu. |
| `gui.access-item-material` | `ENDER_EYE` | Its material. |
| `gui.pagination-size` | `28` | Entries per page (7-28). |
| `gui.sounds` | `true` | Menu click sounds. |
| `gui.click-cooldown-ms` | `200` | Ignore faster clicks. |
| `gui.text-input` | `DIALOG` | `DIALOG` (Paper dialog window) or `CHAT`; falls back to chat automatically if a dialog cannot be shown. |

World-related settings (`unload-after-minutes`, `max-loaded`) apply on reload; the database location requires a
restart.
