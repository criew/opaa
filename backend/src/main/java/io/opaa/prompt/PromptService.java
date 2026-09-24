package io.opaa.prompt;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.asset.AssetAuthorization;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The prompts of a prompt library - its content. Content is reached through the formula alone
 * ({@link AssetAuthorization#requireContentRole}): {@code VIEWER} reads, {@code EDITOR} writes, and
 * the system administration's floor opens nothing here. Each change writes one audit entry naming
 * the prompt and its library; the prompt text never enters the log.
 */
@Service
@Transactional(readOnly = true)
public class PromptService {

  private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final int MAX_NAME_LENGTH = 64;
  private static final int MAX_TITLE_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;
  private static final String UNIQUE_NAME = "uk_prompts_library_name";

  private final PromptLibraryService libraryService;
  private final PromptRepository promptRepository;
  private final AssetAuthorization authorization;
  private final AuditEventRecorder auditEventRecorder;

  PromptService(
      PromptLibraryService libraryService,
      PromptRepository promptRepository,
      AssetAuthorization authorization,
      AuditEventRecorder auditEventRecorder) {
    this.libraryService = libraryService;
    this.promptRepository = promptRepository;
    this.authorization = authorization;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** The library's prompts by sort order, then name. */
  public List<Prompt> list(UUID libraryId, CurrentUser caller) {
    PromptLibrary library = requireContent(libraryId, caller, AssetRole.VIEWER);
    return promptRepository.findByLibraryIdOrderBySortOrderAscNameAsc(library.getId());
  }

  public Prompt get(UUID libraryId, UUID promptId, CurrentUser caller) {
    PromptLibrary library = requireContent(libraryId, caller, AssetRole.VIEWER);
    return load(library, promptId);
  }

  /** Adds a prompt; a name the library already holds is a {@code 409}. */
  @Transactional
  public Prompt create(UUID libraryId, PromptContent requested, CurrentUser caller) {
    PromptLibrary library = requireContent(libraryId, caller, AssetRole.EDITOR);
    PromptContent content = normalized(requested);
    validate(content);
    if (promptRepository.existsByLibraryIdAndName(library.getId(), content.name())) {
      throw nameTaken(content.name());
    }
    Prompt saved = saveUnique(new Prompt(library, content), content.name());
    library.markContentChanged();
    record(AuditEventType.PROMPT_CREATED, library, saved, Map.of("name", saved.getName()), caller);
    return saved;
  }

  /**
   * Replaces a prompt as a whole; {@code PROMPT_CHANGED} names the fields that changed, never their
   * values, and a request that changes nothing writes nothing.
   */
  @Transactional
  public Prompt update(UUID libraryId, UUID promptId, PromptContent requested, CurrentUser caller) {
    PromptLibrary library = requireContent(libraryId, caller, AssetRole.EDITOR);
    Prompt prompt = load(library, promptId);
    PromptContent content = normalized(requested);
    validate(content);
    if (!prompt.getName().equals(content.name())
        && promptRepository.existsByLibraryIdAndName(library.getId(), content.name())) {
      throw nameTaken(content.name());
    }
    List<String> changedFields = changedFields(prompt, content);
    if (changedFields.isEmpty()) {
      return prompt;
    }
    prompt.apply(content);
    Prompt saved = saveUnique(prompt, content.name());
    library.markContentChanged();
    record(
        AuditEventType.PROMPT_CHANGED,
        library,
        saved,
        Map.of("changedFields", changedFields),
        caller);
    return saved;
  }

  @Transactional
  public void delete(UUID libraryId, UUID promptId, CurrentUser caller) {
    PromptLibrary library = requireContent(libraryId, caller, AssetRole.EDITOR);
    Prompt prompt = load(library, promptId);
    record(
        AuditEventType.PROMPT_DELETED, library, prompt, Map.of("name", prompt.getName()), caller);
    promptRepository.delete(prompt);
    library.markContentChanged();
  }

  private PromptLibrary requireContent(UUID libraryId, CurrentUser caller, AssetRole required) {
    PromptLibrary library = libraryService.load(libraryId, caller);
    authorization.requireContentRole(library, caller.id(), caller.isSystemAdmin(), required);
    return library;
  }

  private Prompt load(PromptLibrary library, UUID promptId) {
    return promptRepository
        .findByIdAndLibraryId(promptId, library.getId())
        .orElseThrow(() -> new NotFoundException("Prompt nicht gefunden"));
  }

  /**
   * The unique name is checked above; a concurrent insert of the same name still ends as 409. Any
   * other violated constraint is rethrown unchanged.
   */
  private Prompt saveUnique(Prompt prompt, String name) {
    try {
      return promptRepository.saveAndFlush(prompt);
    } catch (DataIntegrityViolationException violation) {
      if (violatesUniqueName(violation)) {
        throw nameTaken(name);
      }
      throw violation;
    }
  }

  private static boolean violatesUniqueName(Throwable violation) {
    for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException constraint
          && UNIQUE_NAME.equalsIgnoreCase(constraint.getConstraintName())) {
        return true;
      }
    }
    return false;
  }

  /** The content as it is stored: the text with its placeholders normalized. */
  private static PromptContent normalized(PromptContent content) {
    if (content.text() == null) {
      return content;
    }
    return new PromptContent(
        content.name(),
        content.title(),
        content.description(),
        PromptTemplate.normalize(content.text()),
        content.variables(),
        content.sortOrder());
  }

  private static ConflictException nameTaken(String name) {
    return new ConflictException(
        "In dieser Prompt-Bibliothek gibt es bereits einen Prompt mit dem Namen „" + name + "“.");
  }

  private void record(
      AuditEventType type,
      PromptLibrary library,
      Prompt prompt,
      Map<String, Object> details,
      CurrentUser caller) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("promptLibraryId", library.getId().toString());
    payload.putAll(details);
    AuditEvent.Builder event =
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actor(caller.id())
            .type(type)
            .object(AuditObjectType.PROMPT, prompt.getId(), prompt.getName())
            .outcome(AuditOutcome.SUCCESS);
    if (type == AuditEventType.PROMPT_DELETED) {
      event.before(payload);
    } else if (type == AuditEventType.PROMPT_CHANGED) {
      event.before(payload).after(payload);
    } else {
      event.after(payload);
    }
    auditEventRecorder.recordUserAction(event.build());
  }

  private static List<String> changedFields(Prompt prompt, PromptContent content) {
    List<String> changed = new ArrayList<>();
    if (!prompt.getName().equals(content.name())) {
      changed.add("name");
    }
    if (!prompt.getTitle().equals(content.title())) {
      changed.add("title");
    }
    if (!Objects.equals(prompt.getDescription(), content.description())) {
      changed.add("description");
    }
    if (!prompt.getText().equals(content.text())) {
      changed.add("text");
    }
    if (!prompt.getVariables().equals(content.variables())) {
      changed.add("variables");
    }
    if (prompt.getSortOrder() != content.sortOrder()) {
      changed.add("sortOrder");
    }
    return changed;
  }

  private static void validate(PromptContent content) {
    String name = content.name();
    if (name == null || name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
      throw new ValidationException(
          "Der Name eines Prompts besteht aus Kleinbuchstaben, Ziffern und einzelnen"
              + " Bindestrichen, höchstens "
              + MAX_NAME_LENGTH
              + " Zeichen.");
    }
    if (content.title() == null
        || content.title().isBlank()
        || content.title().length() > MAX_TITLE_LENGTH) {
      throw new ValidationException(
          "Der Titel ist erforderlich und hat höchstens " + MAX_TITLE_LENGTH + " Zeichen.");
    }
    if (content.description() != null && content.description().length() > MAX_DESCRIPTION_LENGTH) {
      throw new ValidationException(
          "Die Beschreibung hat höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen.");
    }
    if (content.text() == null
        || content.text().isBlank()
        || content.text().length() > PromptTemplate.MAX_TEXT_LENGTH) {
      throw new ValidationException(
          "Der Text ist erforderlich und hat höchstens "
              + PromptTemplate.MAX_TEXT_LENGTH
              + " Zeichen.");
    }
    PromptTemplate.validate(content.text(), content.variables());
  }
}
