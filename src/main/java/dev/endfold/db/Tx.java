package dev.endfold.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A unit of work bound to one JDBC connection. Repositories receive a Tx and never manage transactions themselves;
 * services decide transaction boundaries through {@link Database#tx}.
 */
public final class Tx {
    private final Connection connection;
    private final Dialect dialect;
    private final List<Runnable> afterCommit = new ArrayList<>();

    public Tx(Connection connection, Dialect dialect) {
        this.connection = connection;
        this.dialect = dialect;
    }

    public Connection connection() { return connection; }

    public Dialect dialect() { return dialect; }

    /** Registers work (typically cache updates) that runs on the DB thread only after a successful commit. */
    public void afterCommit(Runnable r) { afterCommit.add(r); }

    List<Runnable> afterCommitHooks() { return afterCommit; }

    @FunctionalInterface
    public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    public int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params)) {
            return ps.executeUpdate();
        }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(mapper.map(rs));
            return out;
        }
    }

    public <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    public long count(String sql, Object... params) throws SQLException {
        return one(sql, rs -> rs.getLong(1), params).orElse(0L);
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof UUID u) ps.setString(i + 1, u.toString());
            else if (p instanceof Enum<?> e) ps.setString(i + 1, e.name());
            else if (p instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
            else ps.setObject(i + 1, p);
        }
        return ps;
    }

    public static UUID uuid(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? null : UUID.fromString(s);
    }
}
