package dev.endfold.service;

import java.util.Set;
import java.util.function.Supplier;

/** Composition root for all business services. GUIs and commands both go through these objects. */
public final class Services {
    public final ServiceContext ctx;
    public final SpaceService spaces;
    public final MembershipService members;
    public final ProfileService profiles;
    public final ProgressService progress;
    public final LootService loot;
    public final ElytraService elytra;
    public final ShulkerService shulkers;
    public final AuditService audit;

    public Services(ServiceContext ctx, Supplier<Set<AchievementType>> enabledAchievements) {
        this.ctx = ctx;
        this.spaces = new SpaceService(ctx);
        this.members = new MembershipService(ctx);
        this.profiles = new ProfileService(ctx);
        this.progress = new ProgressService(ctx, enabledAchievements);
        this.loot = new LootService(ctx);
        this.elytra = new ElytraService(ctx);
        this.shulkers = new ShulkerService(ctx);
        this.audit = new AuditService(ctx);
    }

    public SpaceDirectory dir() { return ctx.dir; }

    /** Loads the in-memory directory from the database. Called once at startup (blocking is acceptable there). */
    public void loadDirectory() {
        ctx.db.txBlocking(tx -> {
            var spaces = ctx.repo.spaces().listNotDeleted(tx);
            var members = ctx.repo.members().listAll(tx);
            var profiles = ctx.repo.profiles().listAll(tx);
            ctx.dir.load(spaces, members, profiles);
            for (java.util.UUID p : ctx.repo.profiles().playersWithFlag(tx, ProfileService.FLAG_END_PORTAL_USED)) {
                ctx.dir.putFlag(p, ProfileService.FLAG_END_PORTAL_USED);
            }
            return null;
        });
    }
}
