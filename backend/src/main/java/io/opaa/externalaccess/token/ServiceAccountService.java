package io.opaa.externalaccess.token;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The system administration's side of service accounts (ADR-0035, Entscheidung 7): create, change,
 * deactivate, and issue, list and revoke their tokens. Only the controller calls it, and it is
 * {@code SYSTEM_ADMIN} only there.
 *
 * <p>What it deliberately does not do: give a service account a library. That is the consent of the
 * library administration ({@link ServiceAccountConsentService}), and a token carries no selection
 * of its own. Audit entries name accounts and tokens by id, never by their free text.
 */
@Service
public class ServiceAccountService {

  static final int MAX_NAME_LENGTH = 120;
  static final int MAX_DESCRIPTION_LENGTH = 1000;
  static final int MAX_RATE_LIMIT_PER_HOUR = 10_000;

  /** What a default quota looks like in an audit payload, which carries no {@code null}. */
  private static final String CHANNEL_DEFAULT = "DEFAULT";

  private final ServiceAccountRepository accounts;
  private final ServiceAccountTokenRepository tokens;
  private final ServiceAccountLibraryConsentRepository consents;
  private final KnowledgeLibraryRepository libraries;
  private final UserRepository users;
  private final AccountUsability usability;
  private final ExternalAccessSettingsService settings;
  private final LocalAuthKeyService keys;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public ServiceAccountService(
      ServiceAccountRepository accounts,
      ServiceAccountTokenRepository tokens,
      ServiceAccountLibraryConsentRepository consents,
      KnowledgeLibraryRepository libraries,
      UserRepository users,
      AccountUsability usability,
      ExternalAccessSettingsService settings,
      LocalAuthKeyService keys,
      AuditEventRecorder audit,
      Clock clock) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.consents = consents;
    this.libraries = libraries;
    this.users = users;
    this.usability = usability;
    this.settings = settings;
    this.keys = keys;
    this.audit = audit;
    this.clock = clock;
  }

  /** Every service account of the organization, active ones first, then by name. */
  @Transactional(readOnly = true)
  public List<ServiceAccountOverview> list(UUID organizationId) {
    List<ServiceAccount> rows = accounts.findByOrganizationId(organizationId);
    Instant now = clock.instant();
    Map<UUID, List<ServiceAccountToken>> tokensByAccount =
        tokens.findByServiceAccountIdIn(rows.stream().map(ServiceAccount::getId).toList()).stream()
            .collect(Collectors.groupingBy(ServiceAccountToken::getServiceAccountId));
    Map<UUID, User> responsible = responsiblePersons(rows);
    Map<UUID, List<ServiceAccountLibraryConsent>> consentsByAccount =
        withExistingLibraries(
                consents.findByServiceAccountIdInAndEndedAtIsNull(
                    rows.stream().map(ServiceAccount::getId).toList()))
            .stream()
            .collect(Collectors.groupingBy(ServiceAccountLibraryConsent::getServiceAccountId));
    return rows.stream()
        .map(
            account ->
                overview(
                    account,
                    responsible.get(account.getResponsibleUserId()),
                    tokensByAccount.getOrDefault(account.getId(), List.of()),
                    consentsByAccount.getOrDefault(account.getId(), List.of()),
                    now))
        .sorted(
            Comparator.comparing((ServiceAccountOverview view) -> !view.account().isActive())
                .thenComparing(view -> view.account().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(view -> view.account().getId()))
        .toList();
  }

  /** One account with its tokens, newest first, and its open consents by library name. */
  @Transactional(readOnly = true)
  public ServiceAccountDetail detail(UUID organizationId, UUID serviceAccountId) {
    ServiceAccount account = require(organizationId, serviceAccountId);
    Instant now = clock.instant();
    List<ServiceAccountToken> own =
        tokens.findByServiceAccountIdOrderByCreatedAtDesc(account.getId());
    List<ServiceAccountLibraryConsent> named =
        consents.findByServiceAccountIdAndEndedAtIsNull(account.getId());
    Map<UUID, String> names =
        libraries
            .findAllById(named.stream().map(ServiceAccountLibraryConsent::getLibraryId).toList())
            .stream()
            .collect(Collectors.toMap(KnowledgeLibrary::getId, KnowledgeLibrary::getName));
    List<ServiceAccountLibraryConsent> open =
        named.stream().filter(consent -> names.containsKey(consent.getLibraryId())).toList();
    List<ConsentedLibrary> consented =
        open.stream()
            .map(
                consent ->
                    new ConsentedLibrary(
                        consent, names.getOrDefault(consent.getLibraryId(), ""), now))
            .sorted(
                Comparator.comparing(ConsentedLibrary::libraryName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(library -> library.consent().getLibraryId()))
            .toList();
    User responsible =
        account.getResponsibleUserId() == null
            ? null
            : users.findById(account.getResponsibleUserId()).orElse(null);
    return new ServiceAccountDetail(overview(account, responsible, own, open, now), own, consented);
  }

  /** The tokens of one account, newest first. */
  @Transactional(readOnly = true)
  public List<ServiceAccountToken> listTokens(UUID organizationId, UUID serviceAccountId) {
    ServiceAccount account = require(organizationId, serviceAccountId);
    return tokens.findByServiceAccountIdOrderByCreatedAtDesc(account.getId());
  }

  @Transactional
  public ServiceAccountOverview create(
      UUID actorUserId,
      UUID organizationId,
      String rawName,
      String rawDescription,
      UUID responsibleUserId,
      Integer rateLimitPerHour) {
    String name = validName(rawName);
    String description = validDescription(rawDescription);
    User responsible = requireUsableResponsible(responsibleUserId, organizationId);
    validRateLimit(rateLimitPerHour);
    ServiceAccount saved =
        accounts.save(
            new ServiceAccount(
                organizationId,
                name,
                description,
                responsible.getId(),
                rateLimitPerHour,
                clock.instant()));
    audit.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.SERVICE_ACCOUNT_CREATED)
            .object(AuditObjectType.SERVICE_ACCOUNT, saved.getId(), null)
            .subject(AuditSubjectKind.USER, responsible.getId())
            .after(Map.of("rateLimitPerHour", rateLimitLabel(rateLimitPerHour)))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return overview(saved, responsible, List.of(), List.of(), clock.instant());
  }

  /**
   * Replaces name, description, responsible person and quota. A responsible person that changes, or
   * replaces a deleted one, must be an active account of the organization; one that stays may have
   * been locked meanwhile - the account is then shown as lacking her, not refused an edit.
   */
  @Transactional
  public ServiceAccountOverview update(
      UUID actorUserId,
      UUID organizationId,
      UUID serviceAccountId,
      String rawName,
      String rawDescription,
      UUID responsibleUserId,
      Integer rateLimitPerHour) {
    ServiceAccount account = require(organizationId, serviceAccountId);
    if (!account.isActive()) {
      throw new ConflictException("Ein deaktiviertes Dienstkonto lässt sich nicht mehr ändern");
    }
    String name = validName(rawName);
    String description = validDescription(rawDescription);
    validRateLimit(rateLimitPerHour);
    if (responsibleUserId == null) {
      throw new ValidationException("Ein Dienstkonto braucht eine verantwortliche Person");
    }
    if (!responsibleUserId.equals(account.getResponsibleUserId())) {
      requireUsableResponsible(responsibleUserId, organizationId);
    }
    List<String> changed = new ArrayList<>();
    if (!name.equals(account.getName())) {
      changed.add("name");
    }
    if (!Objects.equals(description, account.getDescription())) {
      changed.add("description");
    }
    if (!responsibleUserId.equals(account.getResponsibleUserId())) {
      changed.add("responsiblePerson");
    }
    if (!Objects.equals(rateLimitPerHour, account.getRateLimitPerHour())) {
      changed.add("rateLimitPerHour");
    }
    account.update(name, description, responsibleUserId, rateLimitPerHour);
    ServiceAccount saved = accounts.save(account);
    if (!changed.isEmpty()) {
      audit.recordUserActionOnSubject(
          AuditEvent.builder()
              .organizationId(organizationId)
              .actor(actorUserId)
              .type(AuditEventType.SERVICE_ACCOUNT_CHANGED)
              .object(AuditObjectType.SERVICE_ACCOUNT, saved.getId(), null)
              .subject(AuditSubjectKind.USER, responsibleUserId)
              .after(
                  Map.of("changed", changed, "rateLimitPerHour", rateLimitLabel(rateLimitPerHour)))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return overviewOf(saved);
  }

  /**
   * Deactivates the account for good and ends every live token of it in the same transaction, each
   * with its own entry - the one act that ends the access of a Fachverfahren.
   */
  @Transactional
  public ServiceAccountOverview deactivate(
      UUID actorUserId, UUID organizationId, UUID serviceAccountId) {
    ServiceAccount account = requireLocked(organizationId, serviceAccountId);
    if (!account.isActive()) {
      return overviewOf(account);
    }
    Instant now = clock.instant();
    account.deactivate(now);
    ServiceAccount saved = accounts.save(account);
    int revoked = 0;
    for (ServiceAccountToken token :
        tokens.findByServiceAccountIdAndRevokedAtIsNull(saved.getId())) {
      if (token.revoke(ExternalAccessTokenRevocationReason.ACCOUNT_LIFECYCLE, now)) {
        tokens.save(token);
        recordTokenRevoked(
            token,
            ExternalAccessTokenRevocationReason.ACCOUNT_LIFECYCLE,
            actorUserId,
            organizationId);
        revoked++;
      }
    }
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.SERVICE_ACCOUNT_DEACTIVATED)
            .object(AuditObjectType.SERVICE_ACCOUNT, saved.getId(), null)
            .after(Map.of("revokedTokens", revoked))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return overviewOf(saved);
  }

  /**
   * Issues a token for an active account. The expiry is mandatory, in the future and at most the
   * channel's ceiling away; the plain value exists in the result and nowhere else afterwards.
   */
  @Transactional
  public IssuedServiceAccountToken issueToken(
      UUID actorUserId,
      UUID organizationId,
      UUID serviceAccountId,
      String rawName,
      Instant expiresAt) {
    if (!settings.isEnabled()) {
      throw new AccessDeniedException(
          "Externe KI-Werkzeuge sind für diese Installation ausgeschaltet");
    }
    ServiceAccount account = requireLocked(organizationId, serviceAccountId);
    if (!account.isActive()) {
      throw new ConflictException("Ein deaktiviertes Dienstkonto bekommt keine Tokens mehr");
    }
    String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty()) {
      throw new ValidationException("Ein Zugangstoken braucht einen Namen");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("Der Name darf höchstens 120 Zeichen lang sein");
    }
    Instant now = clock.instant();
    if (expiresAt == null) {
      throw new ValidationException("Ein Zugangstoken braucht ein Ablaufdatum");
    }
    if (!expiresAt.isAfter(now)) {
      throw new ValidationException("Das Ablaufdatum muss in der Zukunft liegen");
    }
    Duration ceiling = Duration.ofDays(settings.current().values().tokenMaxLifetimeDays());
    if (expiresAt.isAfter(now.plus(ceiling))) {
      throw new ValidationException(
          "Das Ablaufdatum überschreitet die Höchstlaufzeit von " + ceiling.toDays() + " Tagen");
    }
    String rawValue = ExternalAccessTokenValues.generate();
    ServiceAccountToken saved =
        tokens.save(
            new ServiceAccountToken(
                account.getId(),
                name,
                ExternalAccessTokenValues.prefixOf(rawValue),
                keys.lookupHash(Purpose.EXTERNAL_ACCESS_TOKEN_LOOKUP, rawValue),
                now,
                expiresAt));
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.API_TOKEN_ISSUED)
            .object(AuditObjectType.API_TOKEN, saved.getId(), null)
            .after(
                Map.of(
                    "serviceAccountId",
                    account.getId().toString(),
                    "expiresAt",
                    expiresAt.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return new IssuedServiceAccountToken(saved, rawValue);
  }

  /** Revokes one token of the account; a token of another account is not found. */
  @Transactional
  public void revokeToken(
      UUID actorUserId, UUID organizationId, UUID serviceAccountId, UUID tokenId) {
    ServiceAccount account = require(organizationId, serviceAccountId);
    ServiceAccountToken token =
        tokens
            .findByIdAndServiceAccountId(tokenId, account.getId())
            .orElseThrow(() -> new NotFoundException("Zugangstoken nicht gefunden"));
    if (token.revoke(ExternalAccessTokenRevocationReason.OWNER, clock.instant())) {
      tokens.save(token);
      recordTokenRevoked(
          token, ExternalAccessTokenRevocationReason.OWNER, actorUserId, organizationId);
    }
  }

  private void recordTokenRevoked(
      ServiceAccountToken token,
      ExternalAccessTokenRevocationReason reason,
      UUID actorUserId,
      UUID organizationId) {
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.API_TOKEN_REVOKED)
            .object(AuditObjectType.API_TOKEN, token.getId(), null)
            .after(
                Map.of(
                    "reason",
                    reason.name(),
                    "by",
                    "ADMIN",
                    "serviceAccountId",
                    token.getServiceAccountId().toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private ServiceAccount requireLocked(UUID organizationId, UUID serviceAccountId) {
    return accounts
        .lockByIdAndOrganizationId(serviceAccountId, organizationId)
        .orElseThrow(() -> new NotFoundException("Dienstkonto nicht gefunden"));
  }

  private ServiceAccount require(UUID organizationId, UUID serviceAccountId) {
    return accounts
        .findByIdAndOrganizationId(serviceAccountId, organizationId)
        .orElseThrow(() -> new NotFoundException("Dienstkonto nicht gefunden"));
  }

  private ServiceAccountOverview overviewOf(ServiceAccount account) {
    User responsible =
        account.getResponsibleUserId() == null
            ? null
            : users.findById(account.getResponsibleUserId()).orElse(null);
    return overview(
        account,
        responsible,
        tokens.findByServiceAccountIdOrderByCreatedAtDesc(account.getId()),
        withExistingLibraries(consents.findByServiceAccountIdAndEndedAtIsNull(account.getId())),
        clock.instant());
  }

  /**
   * The consents whose library still exists. The history has no foreign key on the library, and the
   * daily step ends the rows of a deleted one only on its next run.
   */
  private List<ServiceAccountLibraryConsent> withExistingLibraries(
      List<ServiceAccountLibraryConsent> open) {
    if (open.isEmpty()) {
      return open;
    }
    Set<UUID> existing =
        libraries
            .findAllById(
                open.stream().map(ServiceAccountLibraryConsent::getLibraryId).distinct().toList())
            .stream()
            .map(KnowledgeLibrary::getId)
            .collect(Collectors.toSet());
    return open.stream().filter(consent -> existing.contains(consent.getLibraryId())).toList();
  }

  private ServiceAccountOverview overview(
      ServiceAccount account,
      User responsible,
      List<ServiceAccountToken> own,
      List<ServiceAccountLibraryConsent> open,
      Instant now) {
    List<ServiceAccountToken> active = own.stream().filter(token -> token.isActive(now)).toList();
    List<ServiceAccountLibraryConsent> inForce =
        open.stream().filter(consent -> consent.isInForce(now)).toList();
    Optional<Instant> nextExpiry =
        account.isActive()
            ? java.util.stream.Stream.concat(
                    active.stream().map(ServiceAccountToken::getExpiresAt),
                    inForce.stream().map(ServiceAccountLibraryConsent::getExpiresAt))
                .min(Comparator.naturalOrder())
            : Optional.empty();
    boolean missing = responsible == null || !usability.stateOf(responsible).isUsable();
    int effectiveRateLimit =
        account.getRateLimitPerHour() != null
            ? account.getRateLimitPerHour()
            : settings.current().values().tokenRateLimitPerHour();
    return new ServiceAccountOverview(
        account,
        responsible == null ? null : responsible.getDisplayName(),
        missing,
        effectiveRateLimit,
        account.isActive() ? active.size() : 0,
        account.isActive() ? inForce.size() : 0,
        nextExpiry.orElse(null));
  }

  private Map<UUID, User> responsiblePersons(List<ServiceAccount> rows) {
    List<UUID> ids =
        rows.stream()
            .map(ServiceAccount::getResponsibleUserId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return users.findAllById(ids).stream()
        .collect(
            Collectors.toMap(User::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
  }

  private User requireUsableResponsible(UUID responsibleUserId, UUID organizationId) {
    if (responsibleUserId == null) {
      throw new ValidationException("Ein Dienstkonto braucht eine verantwortliche Person");
    }
    return users
        .findById(responsibleUserId)
        .filter(user -> organizationId.equals(user.getOrganizationId()))
        .filter(user -> usability.stateOf(user).isUsable())
        .orElseThrow(
            () ->
                new ValidationException(
                    "Die verantwortliche Person muss ein aktives Konto dieser Organisation sein"));
  }

  private static String validName(String rawName) {
    String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty()) {
      throw new ValidationException("Ein Dienstkonto braucht einen Namen");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("Der Name darf höchstens 120 Zeichen lang sein");
    }
    return name;
  }

  private static String validDescription(String rawDescription) {
    if (rawDescription == null || rawDescription.isBlank()) {
      return null;
    }
    String description = rawDescription.trim();
    if (description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ValidationException("Die Beschreibung darf höchstens 1000 Zeichen lang sein");
    }
    return description;
  }

  private static void validRateLimit(Integer rateLimitPerHour) {
    if (rateLimitPerHour != null
        && (rateLimitPerHour < 1 || rateLimitPerHour > MAX_RATE_LIMIT_PER_HOUR)) {
      throw new ValidationException(
          "Das Kontingent muss zwischen 1 und 10000 Anfragen je Stunde liegen");
    }
  }

  private static Object rateLimitLabel(Integer rateLimitPerHour) {
    return rateLimitPerHour == null ? CHANNEL_DEFAULT : rateLimitPerHour;
  }

  /**
   * An account as the lists show it. {@code responsiblePersonMissing} covers a deleted, locked or
   * otherwise unusable account; counts and next expiry are zero and empty once it is deactivated.
   */
  public record ServiceAccountOverview(
      ServiceAccount account,
      String responsibleDisplayName,
      boolean responsiblePersonMissing,
      int effectiveRateLimitPerHour,
      int activeTokenCount,
      int consentedLibraryCount,
      Instant nextExpiryAt) {}

  /** The detail: the overview, every token newest first and the open consents. */
  public record ServiceAccountDetail(
      ServiceAccountOverview overview,
      List<ServiceAccountToken> tokens,
      List<ConsentedLibrary> libraries) {}

  /** One open consent of the account with the name of its library and whether it is in force. */
  public record ConsentedLibrary(
      ServiceAccountLibraryConsent consent, String libraryName, boolean inForce) {

    ConsentedLibrary(ServiceAccountLibraryConsent consent, String libraryName, Instant now) {
      this(consent, libraryName, consent.isInForce(now));
    }
  }

  /** A freshly issued token; {@code rawValue} exists here and in the one response, nowhere else. */
  public record IssuedServiceAccountToken(ServiceAccountToken token, String rawValue) {}
}
