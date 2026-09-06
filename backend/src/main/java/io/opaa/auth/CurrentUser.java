package io.opaa.auth;

import io.opaa.api.types.SystemRole;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot of the calling user's identity for the current request. Populated once by {@link
 * UserProvisioningFilter} from the {@link User} row it already loads for every authenticated
 * request, and handed to controllers/services from there — no further {@code
 * findBySubjectAndIssuer} lookup for the caller's own identity.
 *
 * <p>Deliberately a value snapshot, not a reference to the {@link User} entity: it stays valid past
 * the request's persistence context without a lazy-load or detached-entity risk. Code that needs
 * the live entity (e.g. to mutate it) loads it explicitly through {@link UserService} and says so
 * at the call site — this type never substitutes for that.
 *
 * <p>Deliberately not a record and deliberately no public constructor: only an {@code @Caller}-
 * annotated parameter is ever routed to {@link CurrentUserArgumentResolver}, but Spring MVC's
 * catch-all {@code ModelAttributeMethodProcessor} would still bind any type with a resolvable
 * constructor as a command object from request/query parameters if the resolver chain were ever
 * misconfigured (e.g. a test slice missing {@link CurrentUserWebConfig}) - {@code
 * ?systemRole=SYSTEM_ADMIN} would then construct an attacker-chosen identity past every
 * {@code @PreAuthorize} check downstream. Records cannot narrow their canonical constructor below
 * the record's own accessibility (JLS 8.10.4.2), so a plain class is used instead - but a private
 * constructor alone is not enough: {@code BeanUtils.getResolvableConstructor}, which Spring's
 * constructor-based data binding uses, reflectively invokes a class's single declared constructor
 * regardless of its visibility. The private no-arg constructor below exists solely to defeat that:
 * Spring resolves and invokes it first (a no-arg constructor always wins constructor resolution),
 * and it unconditionally throws, so a resolver-chain misconfiguration fails the request instead of
 * silently falling through to attacker-controlled binding. {@link #of} is the construction path for
 * test code outside this package, which never goes through Spring's data binder.
 */
public final class CurrentUser {

  private final UUID id;
  private final UUID organizationId;
  private final SystemRole systemRole;
  private final String displayName;
  private final String email;

  /**
   * Reflection guard, never called by application code - see the class Javadoc. Spring's data
   * binder resolves and invokes exactly this constructor for any attempted command-object binding,
   * and it always fails that attempt.
   */
  private CurrentUser() {
    throw new UnsupportedOperationException(
        "CurrentUser must never be constructed via reflection/data binding");
  }

  private CurrentUser(
      UUID id, UUID organizationId, SystemRole systemRole, String displayName, String email) {
    this.id = id;
    this.organizationId = organizationId;
    this.systemRole = systemRole;
    this.displayName = displayName;
    this.email = email;
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public SystemRole systemRole() {
    return systemRole;
  }

  public String displayName() {
    return displayName;
  }

  /** The address the identity provider asserted at this sign-in; may be {@code null}. */
  public String email() {
    return email;
  }

  public boolean isSystemAdmin() {
    return systemRole == SystemRole.SYSTEM_ADMIN;
  }

  static CurrentUser from(User user) {
    return new CurrentUser(
        user.getId(),
        user.getOrganizationId(),
        user.getSystemRole(),
        user.getDisplayName(),
        user.getEmail());
  }

  /**
   * Test-only construction path for code outside {@code io.opaa.auth} - never used by Spring MVC.
   */
  public static CurrentUser of(
      UUID id, UUID organizationId, SystemRole systemRole, String displayName) {
    return new CurrentUser(id, organizationId, systemRole, displayName, null);
  }

  public static CurrentUser of(
      UUID id, UUID organizationId, SystemRole systemRole, String displayName, String email) {
    return new CurrentUser(id, organizationId, systemRole, displayName, email);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CurrentUser that)) {
      return false;
    }
    return Objects.equals(id, that.id)
        && Objects.equals(organizationId, that.organizationId)
        && systemRole == that.systemRole
        && Objects.equals(displayName, that.displayName)
        && Objects.equals(email, that.email);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, organizationId, systemRole, displayName, email);
  }

  @Override
  public String toString() {
    return "CurrentUser[id="
        + id
        + ", organizationId="
        + organizationId
        + ", systemRole="
        + systemRole
        + ", displayName="
        + displayName
        + ", email="
        + email
        + "]";
  }
}
