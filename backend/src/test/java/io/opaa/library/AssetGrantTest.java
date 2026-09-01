package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code granted_by_user_id} answers "who procured the role this grant carries now" (#1052). These
 * tests pin the two halves of that contract on the entity itself, since {@code
 * LibraryAccessService#holdsIndependentOwnerRole} reads the field to tell a self-procured {@link
 * AssetRole#OWNER} from one somebody else conferred.
 */
class AssetGrantTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  @Test
  void aRealRoleChangeCarriesTheChangerIntoTheGrant() {
    UUID originalGranter = UUID.randomUUID();
    UUID changer = UUID.randomUUID();
    AssetGrant grant = grantOf(AssetRole.VIEWER, null, originalGranter);

    grant.updateRole(AssetRole.OWNER, null, changer);

    assertThat(grant.getRole()).isEqualTo(AssetRole.OWNER);
    assertThat(grant.getGrantedByUserId()).isEqualTo(changer);
  }

  /**
   * An extension of one's own grant must not turn its holder into its own conferrer - that would
   * cost an independent owner the right to lift a diagnostics lock, for a change that conferred
   * nothing.
   */
  @Test
  void anUnchangedRoleLeavesTheConferrerInPlace() {
    UUID originalGranter = UUID.randomUUID();
    UUID holder = UUID.randomUUID();
    Instant extendedExpiry = Instant.now().plus(90, ChronoUnit.DAYS);
    AssetGrant grant = grantOf(AssetRole.OWNER, Instant.now(), originalGranter);

    grant.updateRole(AssetRole.OWNER, extendedExpiry, holder);

    assertThat(grant.getExpiresAt()).isEqualTo(extendedExpiry);
    assertThat(grant.getGrantedByUserId()).isEqualTo(originalGranter);
  }

  private static AssetGrant grantOf(AssetRole role, Instant expiresAt, UUID grantedByUserId) {
    return AssetGrant.forUser(
        LIBRARY_ID, ORGANIZATION_ID, UUID.randomUUID(), role, expiresAt, grantedByUserId);
  }
}
