package io.opaa.template;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The document templates an agent run may fill with the tool {@code fill_template}: the one place
 * that decides which templates a run sees. Asked on every run and again on every call, never
 * cached, so the switch "Vorlagen" and a withdrawn right take effect at once. Today it answers
 * every template the asker may read while the switch is on; an assignment of templates to an agent
 * or a lock of a template narrows the answer here, not in the tool.
 */
public interface RunTemplates {

  /** The templates the asker may fill, by name; empty while the switch "Vorlagen" is off. */
  List<RunTemplate> available(UUID organizationId, UUID userId);

  /**
   * The template with its file, when the asker may fill it right now; empty for an unknown id,
   * another organization's template, one the asker may not read, and any id while the switch is off
   * - all alike.
   */
  Optional<TemplateToFill> forFilling(UUID templateId, UUID organizationId, UUID userId);

  /**
   * A template as a run offers it to the model.
   *
   * @param revision the file's revision ("Fassung") that a filling stamps into its result.
   */
  record RunTemplate(
      UUID id,
      String name,
      String description,
      List<TemplatePlaceholder> placeholders,
      int revision) {

    public RunTemplate {
      placeholders = List.copyOf(placeholders);
    }
  }

  /** A template and its DOCX file as uploaded, never rendered in the backend. */
  record TemplateToFill(RunTemplate template, String fileName, byte[] content) {}
}
