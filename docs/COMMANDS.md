# Commands and permissions

Everything a player needs is available in the menu (`/end`). Commands are shortcuts; without arguments most of them
open the matching menu. Aliases: `/endspace`, `/endspaces`.

## Player commands (`/end`, permission `endfold.use`)

| Command | What it does |
|---|---|
| `/end` | Open the main menu. |
| `/end help` | Short command overview. |
| `/end spaces` | Open **My End Worlds** (`/end list` prints them in chat). |
| `/end solo` | Create (if needed) and select your personal End. *(endfold.create)* |
| `/end create [name]` | No name: open the creation wizard. With a name: create a group EndSpace. *(endfold.create)* |
| `/end select [id]` | Make End portals lead to that EndSpace. Never teleports, never changes memberships. |
| `/end enter [id]` | Travel now to the selected (or given) EndSpace. Subject to `portals.direct-teleport`: by default only after you have entered an End through a real End portal once. |
| `/end exit` | Leave the EndSpace you are in and return to where you entered from. |
| `/end info [id]` | Open the EndSpace's detail menu. |
| `/end members [id]` | Open the members menu (chat list if menus are disabled). |
| `/end invite [player] [id]` | No player: open the player picker. Otherwise invite to the selected/given group. *(endfold.invite)* |
| `/end invites` | Open the invitations menu (incoming and outgoing). |
| `/end accept [invitation]` / `/end decline [invitation]` | Without argument opens the menu; the argument may be the invitation id shown in chat, the EndSpace id or its name. Clicking `[Accept]` in the chat notification does this for you. |
| `/end public` | Browse **Public Expeditions**. *(endfold.public)* |
| `/end public on [open\|approval]` / `/end public off` | List / unlist your selected group (admin+ of that group). |
| `/end join <id>` / `/end request <id>` | Join an open expedition or request to join an approval-required one. *(endfold.join)* |
| `/end requests [id]` | Review join requests for a group you manage. |
| `/end approve <player> [id]` / `/end deny <player> [id]` | Answer a join request. |
| `/end kick <player> [id]` | Remove a member (requires `confirm`). |
| `/end promote <player>` / `/end demote <player>` | Owner: make a member admin or back. |
| `/end transfer <player>` | Owner: hand over ownership (requires `confirm`). |
| `/end rename <name>` | Rename the selected EndSpace (admin+). |
| `/end visibility <public\|private> [open\|approval]` | Same as `/end public on/off`. |
| `/end leave [id]` | Leave an EndSpace (requires `confirm`). Owners must transfer first. |
| `/end disband [id]` | Owner: archive the EndSpace (requires `confirm`). The world is kept and can be restored by staff. |
| `/end achievements` | Open your individual achievements. |
| `/end claim` | Collect rewards that did not fit in your inventory (e.g. an Elytra). |

Destructive commands print a notice first; run the same command again with `confirm` appended within 30 seconds.

## Administration (`/endfold`, alias `/endadmin`, console allowed)

`<space>` accepts the 8-character id (shown in menus and `/endfold list`), the full UUID, the world name or the
EndSpace name.

| Command | Permission | What it does |
|---|---|---|
| `/endfold reload` | `endfold.admin.reload` | Reload `config.yml`, `messages.yml`, `gui.yml`; prints the number of config problems. |
| `/endfold gui` | `endfold.admin.worlds` | Admin world browser GUI. |
| `/endfold list [filter]` | `endfold.admin.worlds` | List EndSpaces (filter: status, name fragment or owner name). |
| `/endfold info <space>` | `endfold.admin.worlds` | World, status, owner, members, seed, dragon kills, gateways. |
| `/endfold members <space>` | `endfold.admin.worlds` | Membership list. |
| `/endfold load <space>` / `unload <space>` | `endfold.admin.worlds` | Load or save+unload a world (players are moved out first). Files are never deleted. |
| `/endfold tp <space>` | `endfold.admin.teleport` | Teleport into any EndSpace (bypasses membership; logged). |
| `/endfold backup <space>` | `endfold.admin.backup` | Zip the world to `plugins/Endfold/backups/<id>/`. |
| `/endfold backups <space>` | `endfold.admin.backup` | List backups. |
| `/endfold restore <space> <file> confirm` | `endfold.admin.backup` | Restore a backup (safety backup first; previous folder moved to trash). |
| `/endfold disband <space> confirm` | `endfold.admin.delete` | Archive a space (members lose access; data kept). |
| `/endfold activate <space>` | `endfold.admin.worlds` | Re-activate an archived or repaired space. |
| `/endfold delete <space> confirm` | `endfold.admin.delete` | Evict, unload, back up, move world to `plugins/Endfold/trash/`, mark DELETED. |
| `/endfold repair` | `endfold.admin.worlds` | Mark spaces with missing folders BROKEN; re-activate BROKEN spaces whose folder is back. |
| `/endfold orphans` | `endfold.admin.worlds` | World folders without a database record (never touched automatically). |
| `/endfold claims <player> <space>` | `endfold.admin.worlds` | A player's loot claims in a space. |
| `/endfold addmember <space> <player>` | `endfold.admin.worlds` | Add a member. |
| `/endfold removemember <space> <player> confirm` | `endfold.admin.worlds` | Remove a member. |
| `/endfold transfer <space> <player> confirm` | `endfold.admin.worlds` | Force an ownership transfer. |
| `/endfold audit [space]` | `endfold.admin.worlds` | Recent audit log entries. |

## Permissions

| Node | Default | Grants |
|---|---|---|
| `endfold.use` | everyone | Endfold portal routing, `/end`, menus, switching between your EndSpaces |
| `endfold.create` | everyone | Create personal and group EndSpaces (also needed for automatic personal End creation) |
| `endfold.invite` | everyone | Invite players (still subject to your role in the group) |
| `endfold.join` | everyone | Join / request to join public expeditions |
| `endfold.public` | everyone | Browse expeditions and list your groups publicly |
| `endfold.bypass.direct-teleport` | op | Use "Enter now" / `/end enter` regardless of `portals.direct-teleport` |
| `endfold.admin` | op | `/endfold` (parent of all admin nodes below) |
| `endfold.admin.reload` | op | Reload configuration |
| `endfold.admin.worlds` | op | Inspect, load/unload, repair, manage memberships |
| `endfold.admin.delete` | op | Archive or delete EndSpaces |
| `endfold.admin.teleport` | op | Enter any EndSpace and the shared End; bypasses access checks |
| `endfold.admin.backup` | op | Create and restore backups |

Roles inside a group (OWNER > ADMIN > MEMBER) are separate from permissions: the owner can promote/demote admins,
transfer ownership, change the member limit and disband; admins can invite, kick members, manage requests, rename and
change visibility; members can enter and (if `members-can-invite: true`) invite.
