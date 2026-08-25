package io.opaa.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Parameter object for {@link AuditEventRecorder}'s three {@code recordXxx} methods, replacing
 * their previous 10-13 positional parameters with named builder calls: a swapped before/after or
 * objectId/subjectId becomes a visible, reorderable line in the diff instead of two adjacent
 * positional arguments that compile either way round. Carries every field any of the three methods
 * can use; each method validates only the subset it actually needs and rejects the fields it does
 * not own being set (see their Javadoc). {@code before}/{@code after} are defensively copied in the
 * constructor, so the instance stays immutable even if the caller mutates the map it passed in
 * afterwards.
 */
public final class AuditEvent {

  private final UUID organizationId;
  private final UUID actorUserId;
  private final String actorRef;
  private final AuditEventType eventType;
  private final AuditObjectType objectType;
  private final UUID objectId;
  private final String objectLabel;
  private final AuditSubjectKind subjectKind;
  private final UUID subjectId;
  private final Map<String, Object> before;
  private final Map<String, Object> after;
  private final AuditOutcome outcome;
  private final String reason;
  private final String correlationRef;

  private AuditEvent(Builder builder) {
    this.organizationId = Objects.requireNonNull(builder.organizationId, "organizationId");
    this.actorUserId = builder.actorUserId;
    this.actorRef = builder.actorRef;
    this.eventType = Objects.requireNonNull(builder.eventType, "type");
    this.objectType = Objects.requireNonNull(builder.objectType, "object type");
    this.objectId = Objects.requireNonNull(builder.objectId, "object id");
    this.objectLabel = builder.objectLabel;
    this.subjectKind = builder.subjectKind;
    this.subjectId = builder.subjectId;
    this.before = copyOf(builder.before);
    this.after = copyOf(builder.after);
    this.outcome = Objects.requireNonNull(builder.outcome, "outcome");
    this.reason = builder.reason;
    this.correlationRef = builder.correlationRef;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * {@code null} stays {@code null} (distinct from an empty map - see {@link AuditEventRecorder}'s
   * {@code toJson}, which maps both to a {@code null} column but the distinction still matters for
   * a caller inspecting the built event itself); a non-null map is defensively copied via {@link
   * Map#copyOf}.
   */
  private static Map<String, Object> copyOf(Map<String, Object> value) {
    return value == null ? null : Map.copyOf(value);
  }

  /**
   * Accessors below are public - besides {@link AuditEventRecorder}'s own use, tests pin an
   * operation's audit fields via an {@code ArgumentCaptor<AuditEvent>} and these getters, rather
   * than reflection or a long chain of positional Mockito matchers.
   */
  public UUID organizationId() {
    return organizationId;
  }

  public UUID actorUserId() {
    return actorUserId;
  }

  public String actorRef() {
    return actorRef;
  }

  public AuditEventType eventType() {
    return eventType;
  }

  public AuditObjectType objectType() {
    return objectType;
  }

  public UUID objectId() {
    return objectId;
  }

  public String objectLabel() {
    return objectLabel;
  }

  public AuditSubjectKind subjectKind() {
    return subjectKind;
  }

  public UUID subjectId() {
    return subjectId;
  }

  public Map<String, Object> before() {
    return before;
  }

  public Map<String, Object> after() {
    return after;
  }

  public AuditOutcome outcome() {
    return outcome;
  }

  public String reason() {
    return reason;
  }

  public String correlationRef() {
    return correlationRef;
  }

  /** Builder for {@link AuditEvent}, obtained via {@link AuditEvent#builder()}. */
  public static final class Builder {
    private UUID organizationId;
    private UUID actorUserId;
    private String actorRef;
    private AuditEventType eventType;
    private AuditObjectType objectType;
    private UUID objectId;
    private String objectLabel;
    private AuditSubjectKind subjectKind;
    private UUID subjectId;
    private Map<String, Object> before;
    private Map<String, Object> after;
    private AuditOutcome outcome;
    private String reason;
    private String correlationRef;

    private Builder() {}

    public Builder organizationId(UUID organizationId) {
      this.organizationId = organizationId;
      return this;
    }

    /**
     * The acting person, required by {@link AuditEventRecorder#recordUserAction} and {@link
     * AuditEventRecorder#recordUserActionOnSubject}. Mutually exclusive with {@link
     * #actorRef(String)}.
     */
    public Builder actor(UUID actorUserId) {
      this.actorUserId = actorUserId;
      return this;
    }

    /**
     * The fixed, non-pseudonymised process label required by {@link
     * AuditEventRecorder#recordSystemProcessAction}. Mutually exclusive with {@link #actor(UUID)}.
     */
    public Builder actorRef(String actorRef) {
      this.actorRef = actorRef;
      return this;
    }

    public Builder type(AuditEventType eventType) {
      this.eventType = eventType;
      return this;
    }

    public Builder object(AuditObjectType objectType, UUID objectId, String objectLabel) {
      this.objectType = objectType;
      this.objectId = objectId;
      this.objectLabel = objectLabel;
      return this;
    }

    /**
     * The affected rights subject - optional, set only for {@link
     * AuditEventRecorder#recordUserActionOnSubject} and subject-carrying {@link
     * AuditEventRecorder#recordSystemProcessAction} calls.
     */
    public Builder subject(AuditSubjectKind subjectKind, UUID subjectId) {
      this.subjectKind = subjectKind;
      this.subjectId = subjectId;
      return this;
    }

    public Builder before(Map<String, Object> before) {
      this.before = before;
      return this;
    }

    public Builder after(Map<String, Object> after) {
      this.after = after;
      return this;
    }

    public Builder outcome(AuditOutcome outcome) {
      this.outcome = outcome;
      return this;
    }

    public Builder reason(String reason) {
      this.reason = reason;
      return this;
    }

    /**
     * Optional - the sync run id linking every entry one {@link
     * AuditEventRecorder#recordSystemProcessAction} run writes together.
     */
    public Builder correlationRef(String correlationRef) {
      this.correlationRef = correlationRef;
      return this;
    }

    public AuditEvent build() {
      return new AuditEvent(this);
    }
  }
}
