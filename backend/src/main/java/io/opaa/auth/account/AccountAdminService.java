package io.opaa.auth.account;

import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalCredentialsRepository;
import io.opaa.auth.local.LocalUserOverview;
import io.opaa.auth.oidc.OidcIssuerUris;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of the account list of the administration (#1601): every account of an
 * organization, local and identity-provider accounts alike. Like {@code LocalUserAdminService} it
 * filters, sorts and pages in memory over the organization's accounts - the population of one
 * organization is loaded by {@code UserRepository#findByOrganizationId} in one query anyway, and
 * the state of a local account comes from {@link LocalUserOverview} so that no second derivation of
 * state or activity class exists. A {@code Pageable} query is the step when the population outgrows
 * a single load. Writes stay where they are: local accounts in {@code LocalUserService}, roles in
 * {@code UserService#updateRole}.
 */
@Service
public class AccountAdminService {

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final OidcProviderRepository providers;
  private final Clock clock;

  public AccountAdminService(
      UserRepository users,
      LocalCredentialsRepository credentials,
      OidcProviderRepository providers,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.providers = providers;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AccountPage list(UUID organizationId, AccountQuery query) {
    List<AccountOverview> matching =
        allOf(organizationId).stream()
            .filter(account -> matches(account, query))
            .sorted(comparator(query))
            .toList();
    int from = (int) Math.min((long) query.page() * query.size(), matching.size());
    int to = (int) Math.min((long) from + query.size(), matching.size());
    return new AccountPage(matching.subList(from, to), matching.size(), query.page(), query.size());
  }

  /**
   * Every account of the organization. A local user without its credentials row is not an account
   * (the same rule as {@code LocalUserService#allOf}); an identity-provider account whose provider
   * row is gone keeps its issuer and appears without a provider.
   */
  @Transactional(readOnly = true)
  public List<AccountOverview> allOf(UUID organizationId) {
    Instant now = clock.instant();
    List<User> all = users.findByOrganizationId(organizationId);
    List<UUID> localIds =
        all.stream().filter(AccountAdminService::isLocal).map(User::getId).toList();
    Map<UUID, LocalCredentials> rows =
        localIds.isEmpty()
            ? Map.of()
            : credentials.findAllById(localIds).stream()
                .collect(Collectors.toMap(LocalCredentials::getUserId, Function.identity()));
    Map<String, OidcProvider> providersByIssuer =
        providers.findAll().stream()
            .filter(provider -> !provider.isLocal())
            .collect(
                Collectors.toMap(
                    provider -> OidcIssuerUris.normalize(provider.getIssuerUri()),
                    Function.identity(),
                    (first, second) -> first));
    List<AccountOverview> result = new ArrayList<>(all.size());
    for (User user : all) {
      if (isLocal(user)) {
        LocalCredentials row = rows.get(user.getId());
        if (row != null) {
          result.add(AccountOverview.local(LocalUserOverview.of(user, row, now)));
        }
      } else {
        result.add(
            AccountOverview.ofProvider(
                user, providersByIssuer.get(OidcIssuerUris.normalize(user.getIssuer()))));
      }
    }
    return List.copyOf(result);
  }

  private static boolean isLocal(User user) {
    return LocalIssuer.URN.equals(user.getIssuer());
  }

  private static boolean matches(AccountOverview account, AccountQuery query) {
    if (query.providerType() != null && account.providerType() != query.providerType()) {
      return false;
    }
    if (query.providerId() != null
        && (account.provider() == null || !query.providerId().equals(account.provider().getId()))) {
      return false;
    }
    if (query.role() != null && account.user().getSystemRole() != query.role()) {
      return false;
    }
    if (query.localOnly()) {
      LocalUserOverview local = account.local();
      if (local == null) {
        return false;
      }
      if (query.status() != null && local.state() != query.status()) {
        return false;
      }
      if (query.withoutExpiry() && local.credentials().getExpiresAt() != null) {
        return false;
      }
      if (query.inactive() && !local.isInactive()) {
        return false;
      }
    }
    if (query.query() != null) {
      String needle = query.query().toLowerCase(Locale.ROOT);
      String email = account.user().getEmail();
      String name = account.user().getDisplayName();
      boolean inEmail = email != null && email.toLowerCase(Locale.ROOT).contains(needle);
      boolean inName = name != null && name.toLowerCase(Locale.ROOT).contains(needle);
      return inEmail || inName;
    }
    return true;
  }

  private static Comparator<AccountOverview> comparator(AccountQuery query) {
    Comparator<AccountOverview> comparator =
        switch (query.sort()) {
          case DISPLAY_NAME ->
              Comparator.comparing(
                  a -> a.user().getDisplayName() == null ? "" : a.user().getDisplayName(),
                  String.CASE_INSENSITIVE_ORDER);
          case EMAIL ->
              Comparator.comparing(
                  a -> a.user().getEmail() == null ? "" : a.user().getEmail(),
                  String.CASE_INSENSITIVE_ORDER);
          case EXPIRES_AT ->
              Comparator.comparing(
                  AccountAdminService::expiresAt, Comparator.nullsLast(Comparator.naturalOrder()));
          case CREATED_AT -> Comparator.comparing(a -> a.user().getCreatedAt());
        };
    if (query.descending()) {
      comparator = comparator.reversed();
    }
    return comparator.thenComparing(a -> a.user().getId());
  }

  /** Only a local account has an expiry date; every other account sorts as "without". */
  private static Instant expiresAt(AccountOverview account) {
    return account.local() == null ? null : account.local().credentials().getExpiresAt();
  }
}
