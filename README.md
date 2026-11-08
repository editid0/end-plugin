# Endfold

**Your own End - alone, with friends, or with the whole server.**

Endfold is a Paper/Purpur plugin that replaces Minecraft's single shared End with *EndSpaces*: independent,
persistent End dimensions. Every EndSpace has its own seed, Ender Dragon, End Cities, gateways, builds and resources,
so late joiners on an SMP get the same End experience as the first players - and nobody loses progress because
someone else got there first.

* **Solo and group EndSpaces** built on one implementation (a solo End is a group of one).
* **Belong to many EndSpaces at once** - your personal End, a friends' End and a public expedition - and choose
  which one your End portals lead to. Switching never deletes, resets or transfers anything.
* **Fair resources**: personal End City chest loot for every player, one Elytra per player per End Ship, renewable
  End City shulkers, repeatable dragon fights with individual participation tracking.
* **GUI-first**: everything is reachable from `/end`; commands are optional shortcuts.
* **Safe by design**: lazy world loading/unloading, crash-safe reward delivery, backups, soft deletion, audit log.

## Requirements

| | |
|---|---|
| Server | **Purpur 26.3** (tested on build 2657) or **Paper 26.3** (uses only Paper API) |
| Java | **25** or newer (required by Minecraft 26.3) |
| Database | SQLite (bundled with Paper/Purpur - nothing to install) |
| Other plugins | none |

`allow-end` in `server.properties`/`bukkit.yml` may stay at its default (`true`); Endfold intercepts End portals
before vanilla uses the shared End. See [docs/LIMITATIONS.md](docs/LIMITATIONS.md) for interactions with other plugins.

## Installation

1. Stop the server and back up your worlds.
2. Copy `Endfold-1.0.0.jar` into `plugins/`.
3. Start the server. Endfold creates `plugins/Endfold/` with `config.yml`, `messages.yml`, `gui.yml` and the
   database `endfold.db`.
4. Optionally adjust `config.yml` and run `/endfold reload`.

Existing players in the shared End (`world_the_end`) are moved back to the overworld when
`portals.block-unmanaged-end-access` is enabled (default). The shared End's files are never modified.

## Building

```bash
./mvnw clean package
```

The plugin jar is written to `target/Endfold-1.0.0.jar`. The build runs the unit test suite (JUnit, real SQLite).
Real-server integration tests are run separately: `./testkit/run-integration.sh` (see [docs/TESTING.md](docs/TESTING.md)).

## Playing

1. Step into an End portal. With default settings your personal End is created automatically and you arrive on its
   obsidian platform.
2. Type `/end` to open the menu:
   * **My End Worlds** - every EndSpace you belong to. Click one to *Select* it (portals lead there), *Enter* it
     now, view members and progress, or manage it.
   * **Create EndSpace** - a personal End or a group End you name yourself.
   * **Public Expeditions** - browse groups that accept new members; join directly or send a request.
   * **Invitations** - accept, decline or cancel invitations without typing ids.
   * **Achievements** - your individual End progression.
3. Leaving an EndSpace through its exit portal returns you to where you entered from.

## Documentation

* [Commands and permissions](docs/COMMANDS.md)
* [Configuration](docs/CONFIGURATION.md)
* [GUI guide](docs/GUI.md)
* [Architecture](docs/ARCHITECTURE.md)
* [Testing and results](docs/TESTING.md)
* [Known limitations](docs/LIMITATIONS.md)

## Naming

"Endfold": the End, folded into many parallel layers - one for each player or party. Before choosing it we checked
Modrinth, GitHub and web search for existing Minecraft projects; no plugin or mod named Endfold was found (November
2026). Rejected candidates: *Voidbound* (several existing Modrinth projects), *Endbound* (existing mod and an Origins
add-on), *Voidhaven*, *Riftwalk*, *Outrealm* (existing GitHub projects / generic), *Shardspace* (close to an existing
"Shardbound" plugin).

## License

GNU General Public License v3.0 - see [LICENSE](LICENSE).
