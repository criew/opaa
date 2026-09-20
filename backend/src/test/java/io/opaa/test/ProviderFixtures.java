package io.opaa.test;

import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.util.UUID;

/**
 * An identity provider row for a test that needs one. Since #1816 every {@code ORG_UNIT} group
 * carries its provider ({@code chk_groups_provider_kind}), so a class using a directory group as a
 * fixture - a diagnostic scope, a read-only group, a membership - needs a provider to hang it on.
 *
 * <p>Deliberately no Spring bean and no {@code @Import}: a class calls this with the repository it
 * already has, so nothing here enters the context's cache key. Each caller removes the row it
 * created again; {@code fk_groups_provider} is {@code RESTRICT}, so its groups go first.
 */
public final class ProviderFixtures {

  private ProviderFixtures() {}

  /** A provider whose groups come from its tokens - the shape most fixtures want. */
  public static OidcProvider tokenProvider(OidcProviderRepository repository) {
    return save(repository, null, false);
  }

  /** A provider with the directory run switched on, at the shipped interval of six hours. */
  public static OidcProvider directoryProvider(OidcProviderRepository repository) {
    return save(repository, null, true);
  }

  /** A provider that reads its groups from the named token claim. */
  public static OidcProvider tokenProvider(OidcProviderRepository repository, String groupsClaim) {
    return save(repository, groupsClaim, false);
  }

  private static OidcProvider save(
      OidcProviderRepository repository, String groupsClaim, boolean directorySync) {
    OidcProvider provider =
        new OidcProvider(
            "Anbieter " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            new OidcClaimMapping(null, null, null, null, null, groupsClaim));
    if (directorySync) {
      provider.configureDirectorySync(true, 360);
    }
    return repository.save(provider);
  }
}
