package io.opaa.indexing.source;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionProfileSupport;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * How a connector stands to connection profiles ("Profilangabe", ADR-0038): whether it admits them,
 * the sign-ins a profile chooses from, what its server address may be and which settings it may
 * set. A connector admitting profiles offers at least one sign-in; one without profiles has no
 * defaults and names a sign-in only where the core signs in for a library's own key.
 */
public record ProfileDeclaration(
    ConnectionProfileSupport support,
    List<SignIn> signIns,
    ServerAddressRule address,
    ProfileDefaults defaults) {

  private static final ProfileDeclaration FORBIDDEN =
      new ProfileDeclaration(
          ConnectionProfileSupport.FORBIDDEN,
          List.of(),
          ServerAddressRule.web(),
          ProfileDefaults.none());

  public ProfileDeclaration {
    Objects.requireNonNull(support, "support");
    Objects.requireNonNull(address, "address");
    signIns = signIns == null ? List.of() : List.copyOf(signIns);
    defaults = defaults == null ? ProfileDefaults.none() : defaults;
    Set<ConnectionAuthMethod> methods = new HashSet<>();
    for (SignIn signIn : signIns) {
      if (!methods.add(signIn.method())) {
        throw new IllegalArgumentException("sign-in " + signIn.method() + " is named twice");
      }
    }
    if (support == ConnectionProfileSupport.FORBIDDEN) {
      if (!defaults.isEmpty()) {
        throw new IllegalArgumentException("a connector without profiles has no profile defaults");
      }
      if (signIns.stream().anyMatch(s -> !(s.details() instanceof ServiceAccountKeyAuth))) {
        throw new IllegalArgumentException(
            "a connector without profiles names only a service account key sign-in");
      }
    } else if (signIns.isEmpty()) {
      throw new IllegalArgumentException("a connector admitting profiles offers a sign-in");
    }
  }

  /** No profiles: what every connector declares until it is connected to profiles. */
  public static ProfileDeclaration forbidden() {
    return FORBIDDEN;
  }

  /** Profiles as {@code support} says, signing in by one of {@code signIns}, at a web address. */
  public static ProfileDeclaration of(ConnectionProfileSupport support, SignIn... signIns) {
    return new ProfileDeclaration(
        support, List.of(signIns), ServerAddressRule.web(), ProfileDefaults.none());
  }

  /** No profiles; the core signs in for a library's own key as {@code auth} says. */
  public static ProfileDeclaration forbiddenWithServiceAccountKey(ServiceAccountKeyAuth auth) {
    return new ProfileDeclaration(
        ConnectionProfileSupport.FORBIDDEN,
        List.of(SignIn.serviceAccountKey(auth)),
        ServerAddressRule.web(),
        ProfileDefaults.none());
  }

  public ProfileDeclaration withAddress(ServerAddressRule rule) {
    return new ProfileDeclaration(support, signIns, rule, defaults);
  }

  public ProfileDeclaration withDefaults(DefaultKey... keys) {
    return new ProfileDeclaration(support, signIns, address, ProfileDefaults.of(keys));
  }

  public boolean admitsProfiles() {
    return support != ConnectionProfileSupport.FORBIDDEN;
  }

  /** The sign-in by {@code method}, empty when the connector does not offer it. */
  public Optional<SignIn> signIn(ConnectionAuthMethod method) {
    return signIns.stream().filter(signIn -> signIn.method() == method).findFirst();
  }

  public boolean offers(ConnectionAuthMethod method) {
    return signIn(method).isPresent();
  }

  /** The service account key the core signs with, {@code null} when the connector has none. */
  public ServiceAccountKeyAuth serviceAccountKey() {
    return signIns.stream()
        .map(SignIn::details)
        .filter(ServiceAccountKeyAuth.class::isInstance)
        .map(ServiceAccountKeyAuth.class::cast)
        .findFirst()
        .orElse(null);
  }
}
