package io.opaa.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
  Optional<User> findBySubjectAndIssuer(String subject, String issuer);

  Optional<User> findByEmail(String email);

  /**
   * Resolves directory group members to their {@link User} rows for #237's directory
   * synchronisation, scoped to the organization so a subject from another tenant can never be
   * matched in - the same boundary {@code GroupMembershipRepository} enforces for group reads.
   * Matches on {@code subject} alone (not {@code subject} + {@code issuer}): the MVP runs a single
   * OIDC issuer per organization (see {@code AuthProperties}), and requiring an issuer here would
   * force the directory sync to carry issuer configuration that duplicates what auth already knows.
   */
  List<User> findByOrganizationIdAndSubjectIn(UUID organizationId, Collection<String> subjects);
}
