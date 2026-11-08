package dev.endfold.db.sql;

import dev.endfold.repo.AuditRepository;
import dev.endfold.repo.ElytraRepository;
import dev.endfold.repo.InvitationRepository;
import dev.endfold.repo.LootRepository;
import dev.endfold.repo.MembershipRepository;
import dev.endfold.repo.ProfileRepository;
import dev.endfold.repo.ProgressRepository;
import dev.endfold.repo.RequestRepository;
import dev.endfold.repo.ReturnLocationRepository;
import dev.endfold.repo.ShulkerRepository;
import dev.endfold.repo.SpaceRepository;

/** Bundle of repository implementations. A PostgreSQL backend can supply its own bundle without touching services. */
public record Repositories(
        SpaceRepository spaces,
        MembershipRepository members,
        ProfileRepository profiles,
        InvitationRepository invitations,
        RequestRepository requests,
        ProgressRepository progress,
        LootRepository loot,
        ElytraRepository elytra,
        ReturnLocationRepository returns,
        ShulkerRepository shulkers,
        AuditRepository audit) {

    public static Repositories sql() {
        return new Repositories(new SqlSpaceRepository(), new SqlMembershipRepository(), new SqlProfileRepository(),
                new SqlInvitationRepository(), new SqlRequestRepository(), new SqlProgressRepository(), new SqlLootRepository(),
                new SqlElytraRepository(), new SqlReturnLocationRepository(), new SqlShulkerRepository(), new SqlAuditRepository());
    }
}
