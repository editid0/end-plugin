package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.BlockPos;
import dev.endfold.repo.LootRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqlLootRepository implements LootRepository {
    private static Container mapContainer(ResultSet rs) throws SQLException {
        return new Container(Tx.uuid(rs, "space_id"), new BlockPos(rs.getInt("x"), rs.getInt("y"), rs.getInt("z")), rs.getString("loot_table"));
    }

    @Override public boolean registerContainer(Tx tx, UUID spaceId, BlockPos p, String lootTable, long now) throws SQLException {
        return tx.update("INSERT INTO end_loot_containers(space_id, x, y, z, loot_table, registered_at) VALUES (?,?,?,?,?,?) "
                + "ON CONFLICT(space_id, x, y, z) DO NOTHING", spaceId, p.x(), p.y(), p.z(), lootTable, now) == 1;
    }

    @Override public Optional<Container> container(Tx tx, UUID spaceId, BlockPos p) throws SQLException {
        return tx.one("SELECT * FROM end_loot_containers WHERE space_id=? AND x=? AND y=? AND z=?", SqlLootRepository::mapContainer, spaceId, p.x(), p.y(), p.z());
    }

    @Override public List<Container> containers(Tx tx, UUID spaceId) throws SQLException {
        return tx.query("SELECT * FROM end_loot_containers WHERE space_id=?", SqlLootRepository::mapContainer, spaceId);
    }

    @Override public boolean claim(Tx tx, UUID spaceId, UUID player, String key, long now) throws SQLException {
        return tx.update("INSERT INTO end_loot_claims(space_id, player_uuid, claim_key, claimed_at) VALUES (?,?,?,?) "
                + "ON CONFLICT(space_id, player_uuid, claim_key) DO NOTHING", spaceId, player, key, now) == 1;
    }

    @Override public boolean hasClaim(Tx tx, UUID spaceId, UUID player, String key) throws SQLException {
        return tx.count("SELECT COUNT(*) FROM end_loot_claims WHERE space_id=? AND player_uuid=? AND claim_key=?", spaceId, player, key) > 0;
    }

    @Override public List<String> claims(Tx tx, UUID spaceId, UUID player) throws SQLException {
        return tx.query("SELECT claim_key FROM end_loot_claims WHERE space_id=? AND player_uuid=?", rs -> rs.getString(1), spaceId, player);
    }

    @Override public Optional<PersonalInventory> inventory(Tx tx, UUID spaceId, UUID player, BlockPos p) throws SQLException {
        return tx.one("SELECT * FROM end_personal_inventories WHERE space_id=? AND player_uuid=? AND x=? AND y=? AND z=?",
                rs -> new PersonalInventory(spaceId, player, p, rs.getString("loot_table"), rs.getInt("size"), rs.getBytes("contents"), rs.getLong("rev")),
                spaceId, player, p.x(), p.y(), p.z());
    }

    @Override public void insertInventory(Tx tx, PersonalInventory inv, long now) throws SQLException {
        tx.update("INSERT INTO end_personal_inventories(space_id, player_uuid, x, y, z, loot_table, size, contents, rev, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                inv.spaceId(), inv.player(), inv.pos().x(), inv.pos().y(), inv.pos().z(), inv.lootTable(), inv.size(), inv.contents(), inv.rev(), now, now);
    }

    @Override public long saveInventory(Tx tx, UUID spaceId, UUID player, BlockPos p, byte[] contents, long expectedRev, long now) throws SQLException {
        int n = tx.update("UPDATE end_personal_inventories SET contents=?, rev=rev+1, updated_at=? WHERE space_id=? AND player_uuid=? AND x=? AND y=? AND z=? AND rev=?",
                contents, now, spaceId, player, p.x(), p.y(), p.z(), expectedRev);
        return n == 1 ? expectedRev + 1 : -1;
    }

    @Override public int countInventories(Tx tx, UUID player) throws SQLException {
        return (int) tx.count("SELECT COUNT(*) FROM end_personal_inventories WHERE player_uuid=?", player);
    }
}
