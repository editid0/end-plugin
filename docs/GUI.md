# GUI guide

Endfold is designed to be used entirely from menus. `/end` opens the main menu; with `gui.use-access-item: true`
players also receive an Ender Eye that opens it with a right-click (it cannot be dropped, stored or lost on death).

All texts, icons and titles are in `plugins/Endfold/gui.yml` (`gui.icons.<name>` = item material).

## Vocabulary used everywhere

| Word | Meaning |
|---|---|
| **Select** | Make your End portals lead to this EndSpace. Nothing else changes. |
| **Enter** | Travel there right now. |
| **Return** | Go back to the overworld spot you came from. You stay a member. |
| **Leave** | Give up your membership (asks for confirmation). Your other EndSpaces are unaffected. |
| **Disband** | Owner only: archive the EndSpace. The world is kept and staff can restore it. |

A *How Endfold works* book in the main menu explains these terms to new players.

## Menus

| Menu | Opened by | Contents |
|---|---|---|
| **Main** | `/end` | My End Worlds, Create EndSpace, Public Expeditions, Invitations (with pending count), Achievements, Enter selected End, Return (inside an End), help book, Administration (staff). |
| **My End Worlds** | `/end spaces` | One entry per EndSpace: name, SOLO/GROUP, your role, owner, members/max, loaded or sleeping, dragon progress, selected mark. Left-click: details; right-click: select; shift-click: enter. Paginated. |
| **EndSpace** | click an entry, `/end info` | Select, Enter now, Return, Members, Invite, Progression (shared), Settings, Join requests, pending invitations, Leave, Disband (by role). |
| **Settings** | EndSpace menu (admin+) | Rename (text input), public/private, join mode OPEN/APPROVAL, member limit +/- (owner). |
| **Progression** | EndSpace menu | Shared progress: dragon defeated, kills, gateways - clearly separated from individual achievements. |
| **Members** | `/end members` | Player heads with roles. Click a member: promote/demote (owner), kick (confirm), transfer ownership (confirm). |
| **Invite a player** | `/end invite` | Online players who are not members and not already invited, plus *Find a player* by name. |
| **Create EndSpace** | `/end create` | Choose Solo or Group -> name (text input) -> review -> confirm/cancel. Opens the new EndSpace. |
| **Invitations** | `/end invites` | Tabs *Incoming* (accept/decline) and *Outgoing* (cancel), showing inviter, EndSpace, owner, sent and expiry dates. |
| **Public Expeditions** | `/end public` | Paginated list of public groups: name, owner, members/max, dragon progress, join mode. Filter (all/open/approval) and sort (members/newest/name). Click for details: Join, Request to join, Cancel request. |
| **Join requests** | EndSpace menu, `/end requests` | Approve or deny pending requests (admin+). |
| **Achievements** | `/end achievements` | Your individual achievements (unlocked/locked, how to unlock, date) and personal statistics. |
| **Administration** | `/endfold gui` | All EndSpaces with status filter; per space: info, load/unload, teleport, backup, archive/activate, delete (confirm), repair. |
| **Confirm** | destructive actions | Explains the consequence; dangerous actions need the confirm button. |

Chat notifications for invitations and join requests contain clickable `[Accept]`, `[Decline]`, `[Review]` buttons,
so nobody has to copy ids.

## Text input

Names are entered in a Paper dialog window with a text field (`gui.text-input: DIALOG`). If the dialog cannot be
shown, or `CHAT` is configured, the menu closes and the next chat message is used (`cancel` aborts, 60 s timeout);
the menu reopens afterwards.

## Achievements

| Key | How to unlock |
|---|---|
| `first_arrival` | Enter any EndSpace. |
| `dragon_participant` | Take part in defeating an Ender Dragon. |
| `dragon_final_blow` | Land the killing blow on an Ender Dragon. |
| `dragon_veteran` | Take part in 5 dragon victories. |
| `city_explorer` | Open your first End City treasure chest. |
| `treasure_hunter` | Loot 10 End City treasure chests. |
| `wings_of_the_void` | Claim an Elytra from an End Ship. |
| `shell_collector` | Defeat 10 Shulkers in your EndSpaces. |
| `expeditionist` | Be a member of 3 EndSpaces at once. |

Disable any of them with `progression.achievements.disabled`.

## Engineering notes

* One generic listener cancels every click and drag while a menu is open (including shift-click, number keys,
  offhand swap, double-click collect, creative actions and clicks in the player's own inventory), so menu items can
  never be taken. Each menu is its own class built from reusable components (`Menu`, `PaginatedMenu`, `ConfirmMenu`,
  `PlayerSelectMenu`, `TextPrompt`).
* Clicks are rate-limited and a per-player lock prevents an action from running twice.
* Data loaded asynchronously (progress, invitations) shows a loading state and is only rendered if the same menu is
  still open.
* Roles and memberships are re-read on every render and click; the services re-validate everything again, so a stale
  menu can never perform an unauthorised action.
* Menus close on reload/shutdown; nothing is kept for players who log out.
