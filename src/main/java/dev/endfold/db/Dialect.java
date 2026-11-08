package dev.endfold.db;

/** SQL dialect differences. Business logic never branches on dialect; only DDL placeholders and upserts do. */
public enum Dialect {
    SQLITE("INTEGER PRIMARY KEY AUTOINCREMENT", "BLOB"),
    POSTGRES("BIGSERIAL PRIMARY KEY", "BYTEA");

    private final String autoId;
    private final String blob;

    Dialect(String autoId, String blob) {
        this.autoId = autoId;
        this.blob = blob;
    }

    public String resolve(String sql) {
        return sql.replace("${AUTOID}", autoId).replace("${BLOB}", blob);
    }
}
