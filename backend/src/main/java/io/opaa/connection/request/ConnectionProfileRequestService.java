package io.opaa.connection.request;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectionProfileService;
import io.opaa.connection.profile.ConnectionProfileValues;
import io.opaa.connection.profile.ServerAddress;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.SourceType;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connection profile requests ("Zugangswunsch"): a person names a source type and a server address,
 * the system administration of the organization is notified and resolves the request, and the
 * person is notified of the outcome. The address is normalized by the type's rule, never called.
 * Who may submit is decided by the caller; resolving is system administration only. The person's
 * reason never reaches a log, a notification or the audit trail.
 */
@Service
@Transactional(readOnly = true)
public class ConnectionProfileRequestService {

  /** Refusal of a request beyond the ceiling of open requests per person. */
  public static final String OPEN_LIMIT = "CONNECTION_PROFILE_REQUESTS_OPEN_LIMIT";

  /** Refusal to resolve a request that is no longer open. */
  public static final String NOT_OPEN = "CONNECTION_PROFILE_REQUEST_NOT_OPEN";

  static final Duration WINDOW = Duration.ofHours(1);

  private static final int MAX_TEXT_LENGTH = 500;

  /** A server address needs no more; it also keeps audit label and notification bodies short. */
  static final int MAX_URL_LENGTH = 300;

  private static final int MAX_AUDIT_LABEL_LENGTH = 500;
  private static final int MAX_NOTIFICATION_BODY_LENGTH = 2000;
  private static final int MAX_PAGE_SIZE = 100;

  private final ConnectionProfileRequestRepository requests;
  private final ConnectionProfileRepository profiles;
  private final ConnectionProfileService profileService;
  private final SourceConnectorRegistry connectors;
  private final UserRepository users;
  private final NotificationService notifications;
  private final AuditEventRecorder audit;
  private final ProfileRequestProperties limits;
  private final Clock clock;

  public ConnectionProfileRequestService(
      ConnectionProfileRequestRepository requests,
      ConnectionProfileRepository profiles,
      ConnectionProfileService profileService,
      SourceConnectorRegistry connectors,
      UserRepository users,
      NotificationService notifications,
      AuditEventRecorder audit,
      ProfileRequestProperties limits,
      Clock clock) {
    this.requests = requests;
    this.profiles = profiles;
    this.profileService = profileService;
    this.connectors = connectors;
    this.users = users;
    this.notifications = notifications;
    this.audit = audit;
    this.limits = limits;
    this.clock = clock;
  }

  /**
   * Submits a request, or returns the caller's open one for the same type and address unchanged
   * ({@link Submission#created()} false). Refused with 429 beyond the hourly budget and with 409
   * {@value #OPEN_LIMIT} beyond the ceiling of open requests.
   */
  @Transactional
  public Submission submit(CurrentUser caller, SourceType type, String serverUrl, String reason) {
    SourceConnectorDescriptor descriptor = descriptorAdmittingProfiles(type);
    String address = ServerAddress.normalize(serverUrl, descriptor.profileDeclaration().address());
    if (address.length() > MAX_URL_LENGTH) {
      throw new ValidationException(
          "serverUrl darf höchstens " + MAX_URL_LENGTH + " Zeichen umfassen");
    }
    String text = text(reason, "reason");
    requests.lockSubmissionsOf(caller.id());
    Optional<ConnectionProfileRequest> open =
        requests.findByRequestedByAndSourceTypeAndServerUrlAndState(
            caller.id(), type, address, ProfileRequestState.OPEN);
    if (open.isPresent()) {
      return new Submission(view(open.get()), false);
    }
    Instant now = clock.instant();
    requireHourlyBudget(caller, now);
    if (requests.countByRequestedByAndState(caller.id(), ProfileRequestState.OPEN)
        >= limits.maxOpen()) {
      throw new ConflictException(
          "Sie haben bereits "
              + limits.maxOpen()
              + " offene Zugangswünsche. Ein weiterer ist möglich, sobald die Systemverwaltung"
              + " einen davon erledigt oder abgelehnt hat.",
          OPEN_LIMIT);
    }
    ConnectionProfileRequest request =
        requests.save(
            new ConnectionProfileRequest(
                caller.organizationId(), type, address, text, caller.id(), now));
    notifyAdministrators(caller, request, descriptor.displayName());
    return new Submission(view(request), true);
  }

  /** The caller's own requests, newest first, at most the 50 newest. */
  public List<RequestView> mine(CurrentUser caller) {
    return views(requests.findTop50ByRequestedByOrderByCreatedAtDesc(caller.id()));
  }

  /**
   * One page of the organization's requests in {@code state} (every state for null), oldest first.
   */
  public RequestPage page(CurrentUser caller, ProfileRequestState state, int page, int size) {
    if (page < 0) {
      throw new ValidationException("page darf nicht negativ sein");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException("size muss zwischen 1 und " + MAX_PAGE_SIZE + " liegen");
    }
    PageRequest pageable =
        PageRequest.of(page, size, Sort.by("createdAt").ascending().and(Sort.by("id")));
    Page<ConnectionProfileRequest> found =
        state == null
            ? requests.findByOrganizationId(caller.organizationId(), pageable)
            : requests.findByOrganizationIdAndState(caller.organizationId(), state, pageable);
    return new RequestPage(views(found.getContent()), found.getTotalElements(), page, size);
  }

  /**
   * Resolves an open request of the caller's organization as DONE, optionally naming the profile
   * serving it, or DECLINED. 404 outside the organization, 409 {@value #NOT_OPEN} once resolved.
   */
  @Transactional
  public RequestView resolve(
      CurrentUser caller,
      UUID requestId,
      ProfileRequestState outcome,
      UUID profileId,
      String answer) {
    if (outcome == null || outcome == ProfileRequestState.OPEN) {
      throw new ValidationException("state muss DONE oder DECLINED sein");
    }
    if (profileId != null && outcome != ProfileRequestState.DONE) {
      throw new ValidationException("Ein Zugang gehört nur zu einem erledigten Wunsch");
    }
    ConnectionProfileRequest request = openRequest(caller, requestId);
    if (profileId != null) {
      ConnectionProfile profile =
          profiles
              .findById(profileId)
              .orElseThrow(() -> new ValidationException("Der genannte Zugang existiert nicht"));
      requireSameType(request, profile.getSourceType());
    }
    return close(caller, request, outcome, profileId, text(answer, "answer"));
  }

  /**
   * Creates a profile serving the open request {@code requestId} and resolves the request as DONE
   * with it, in one transaction: neither happens without the other.
   */
  @Transactional
  public ConnectionProfile createProfileFor(
      CurrentUser caller,
      UUID requestId,
      SourceType sourceType,
      ConnectionProfileValues values,
      String secret) {
    ConnectionProfileRequest request = openRequest(caller, requestId);
    requireSameType(request, sourceType);
    ConnectionProfile profile = profileService.create(caller, sourceType, values, secret);
    close(caller, request, ProfileRequestState.DONE, profile.getId(), null);
    return profile;
  }

  private ConnectionProfileRequest openRequest(CurrentUser caller, UUID requestId) {
    ConnectionProfileRequest request =
        requests
            .findLockedByIdAndOrganizationId(requestId, caller.organizationId())
            .orElseThrow(() -> new NotFoundException("Zugangswunsch nicht gefunden"));
    if (request.getState() != ProfileRequestState.OPEN) {
      throw new ConflictException("Der Zugangswunsch ist bereits erledigt", NOT_OPEN);
    }
    return request;
  }

  private RequestView close(
      CurrentUser caller,
      ConnectionProfileRequest request,
      ProfileRequestState outcome,
      UUID profileId,
      String answer) {
    request.resolve(outcome, caller.id(), clock.instant(), profileId, answer);
    requests.save(request);
    record(caller, request);
    notifyRequester(request);
    return view(request);
  }

  private void requireHourlyBudget(CurrentUser caller, Instant now) {
    List<ConnectionProfileRequest> recent =
        requests.findByRequestedByAndCreatedAtAfterOrderByCreatedAtAsc(
            caller.id(), now.minus(WINDOW));
    if (recent.size() < limits.maxPerHour()) {
      return;
    }
    Instant free = recent.get(recent.size() - limits.maxPerHour()).getCreatedAt().plus(WINDOW);
    long seconds = Math.max(1, Duration.between(now, free).toSeconds());
    throw new TooManyRequestsException(
        "Sie haben in der letzten Stunde bereits "
            + limits.maxPerHour()
            + " Zugangswünsche gestellt. Bitte versuchen Sie es später erneut.",
        seconds);
  }

  private static void requireSameType(ConnectionProfileRequest request, SourceType type) {
    if (!request.getSourceType().equals(type)) {
      throw new ValidationException(
          "Der Zugang muss zur Quellart des Wunschs gehören ("
              + request.getSourceType().key()
              + ")");
    }
  }

  private SourceConnectorDescriptor descriptorAdmittingProfiles(SourceType type) {
    if (type == null) {
      throw new ValidationException("sourceType ist erforderlich");
    }
    SourceConnectorDescriptor descriptor =
        connectors
            .find(type)
            .orElseThrow(() -> new ValidationException("Unbekannte Quellart " + type.key()))
            .descriptor();
    if (!descriptor.admitsProfiles()) {
      throw new ValidationException(
          "Für die Quellart " + descriptor.displayName() + " gibt es keine Zugänge");
    }
    return descriptor;
  }

  private void notifyAdministrators(
      CurrentUser caller, ConnectionProfileRequest request, String typeName) {
    String body =
        nameOf(caller.displayName())
            + " wünscht einen Zugang für die Quellart „"
            + typeName
            + "“ mit der Server-Adresse "
            + request.getServerUrl()
            + ". Sie finden den Wunsch unter Administration → Zugänge.";
    for (User administrator :
        users.findByOrganizationIdAndSystemRole(
            request.getOrganizationId(), SystemRole.SYSTEM_ADMIN)) {
      notifications.notify(
          request.getOrganizationId(),
          administrator.getId(),
          NotificationType.CONNECTION_PROFILE_REQUESTED,
          AuditObjectType.SYSTEM_SETTING,
          request.getId(),
          "Neuer Zugangswunsch",
          shortened(body, MAX_NOTIFICATION_BODY_LENGTH));
    }
  }

  private void notifyRequester(ConnectionProfileRequest request) {
    boolean done = request.getState() == ProfileRequestState.DONE;
    StringBuilder body =
        new StringBuilder("Ihr Wunsch nach einem Zugang für ")
            .append(request.getServerUrl())
            .append(" (Quellart „")
            .append(typeName(request.getSourceType()))
            .append("“) wurde ")
            .append(done ? "erledigt." : "abgelehnt.");
    if (request.getProfileId() != null) {
      profiles
          .findById(request.getProfileId())
          .ifPresent(
              profile ->
                  body.append(" Zugang: „")
                      .append(profile.getName())
                      .append("“. Ob Sie ihn nutzen dürfen, zeigt der Wissens-Assistent;")
                      .append(" Freigaben erteilt die Systemverwaltung."));
    }
    if (request.getAnswer() != null) {
      body.append(" Antwort der Systemverwaltung: ").append(request.getAnswer());
    }
    notifications.notify(
        request.getOrganizationId(),
        request.getRequestedBy(),
        NotificationType.CONNECTION_PROFILE_REQUEST_RESOLVED,
        AuditObjectType.SYSTEM_SETTING,
        request.getId(),
        done ? "Ihr Zugangswunsch ist erledigt" : "Ihr Zugangswunsch wurde abgelehnt",
        shortened(body.toString(), MAX_NOTIFICATION_BODY_LENGTH));
  }

  /** State, type, address and profile only - never the reason or the answer text. */
  private void record(CurrentUser caller, ConnectionProfileRequest request) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("state", request.getState().name());
    after.put("sourceType", request.getSourceType().key());
    after.put("serverUrl", request.getServerUrl());
    after.put("profileId", request.getProfileId() == null ? "" : request.getProfileId().toString());
    after.put("answerSet", request.getAnswer() != null);
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(AuditEventType.CONNECTION_PROFILE_REQUEST_RESOLVED)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                request.getId(),
                shortened("Zugangswunsch " + request.getServerUrl(), MAX_AUDIT_LABEL_LENGTH))
            .before(Map.of("state", ProfileRequestState.OPEN.name()))
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private String typeName(SourceType type) {
    return connectors
        .find(type)
        .map(connector -> connector.descriptor().displayName())
        .orElse(type.key());
  }

  private RequestView view(ConnectionProfileRequest request) {
    return views(List.of(request)).get(0);
  }

  /** The views of {@code found}, with one query each for the names of persons and profiles. */
  private List<RequestView> views(List<ConnectionProfileRequest> found) {
    Map<UUID, String> persons =
        byId(
            users.findAllById(
                found.stream().map(ConnectionProfileRequest::getRequestedBy).distinct().toList()),
            User::getId,
            user -> nameOf(user.getDisplayName()));
    Map<UUID, String> profileNames =
        byId(
            profiles.findAllById(
                found.stream()
                    .map(ConnectionProfileRequest::getProfileId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList()),
            ConnectionProfile::getId,
            ConnectionProfile::getName);
    return found.stream()
        .map(
            request ->
                new RequestView(
                    request,
                    persons.getOrDefault(request.getRequestedBy(), nameOf(null)),
                    request.getProfileId() == null
                        ? null
                        : profileNames.get(request.getProfileId())))
        .toList();
  }

  private static <T> Map<UUID, String> byId(
      Collection<T> rows, Function<T, UUID> id, Function<T, String> name) {
    return rows.stream().collect(Collectors.toMap(id, name, (first, second) -> first));
  }

  /**
   * {@code value} cut to {@code max} characters, visibly with an ellipsis, for a bounded column.
   */
  private static String shortened(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max - 1) + "…";
  }

  private static String nameOf(String displayName) {
    return displayName == null || displayName.isBlank() ? "Eine Person" : displayName;
  }

  private static String text(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.length() > MAX_TEXT_LENGTH) {
      throw new ValidationException(
          field + " darf höchstens " + MAX_TEXT_LENGTH + " Zeichen umfassen");
    }
    return trimmed;
  }

  /** A request with the display name of its requester and the name of the profile serving it. */
  public record RequestView(
      ConnectionProfileRequest request, String requestedByName, String profileName) {}

  /** {@code created} false: the same request was already open and is returned unchanged. */
  public record Submission(RequestView view, boolean created) {}

  /** One page of requests and the number matching over all pages. */
  public record RequestPage(List<RequestView> items, long total, int page, int size) {}
}
