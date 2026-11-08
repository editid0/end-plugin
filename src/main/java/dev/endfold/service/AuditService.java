package dev.endfold.service;

import dev.endfold.repo.AuditRepository.Entry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AuditService {
    private final ServiceContext ctx;

    public AuditService(ServiceContext ctx) { this.ctx = ctx; }

    public CompletableFuture<Result<Void>> log(UUID spaceId, UUID actor, String action, String detail) {
        ctx.logger.info("[audit] " + action + " space=" + spaceId + " actor=" + actor + (detail == null || detail.isEmpty() ? "" : " " + detail));
        return ctx.tx("audit", tx -> {
            ctx.audit(tx, spaceId, actor, action, detail);
            return null;
        });
    }

    public CompletableFuture<Result<List<Entry>>> recent(UUID spaceId, int limit) {
        return ctx.tx("audit-recent", tx -> ctx.repo.audit().recent(tx, spaceId, limit));
    }
}
