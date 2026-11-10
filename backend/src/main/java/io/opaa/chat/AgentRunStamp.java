package io.opaa.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The version stamp of an agent answer (Fassungsstempel), kept with the answer in its agent run
 * (ADR-0070, Entscheidung 4, Bestand a) and deleted with the chat: which agent in which revision,
 * the libraries the run could search, the templates it filled and the model - each as it was at the
 * time of the run. Every stamped answer is an unchecked draft.
 *
 * @param libraries the run's search scope by id and name at the time, never a library the asker
 *     could not read.
 * @param templates the templates the run filled, each with the revision it filled.
 * @param model the chat model's identifier, {@code null} when unknown.
 */
public record AgentRunStamp(
    UUID agentId,
    String agentName,
    int agentRevision,
    List<SearchedLibraryRef> libraries,
    List<TemplateRef> templates,
    String model) {

  /** The draft notice an answer and a result file carry. */
  public static final String DRAFT_NOTICE = "Entwurf, nicht geprüft";

  public AgentRunStamp {
    libraries = libraries == null ? List.of() : List.copyOf(libraries);
    templates = templates == null ? List.of() : List.copyOf(templates);
  }

  /** This stamp with {@code template} added once; a template filled twice is named once. */
  public AgentRunStamp withTemplate(TemplateRef template) {
    if (templates.contains(template)) {
      return this;
    }
    List<TemplateRef> more = new ArrayList<>(templates);
    more.add(template);
    return new AgentRunStamp(agentId, agentName, agentRevision, libraries, more, model);
  }

  /** The draft notice with agent and revision, for a copied or exported answer. */
  public String shortText() {
    return DRAFT_NOTICE + " · Agent „" + agentName + "“, Fassung " + agentRevision;
  }

  /**
   * The stamp as one line for a result file, led by the draft notice: written into its footers and
   * document properties, so it travels with the file. {@code filled} is the template of this file,
   * {@code null} for none.
   */
  public String documentText(TemplateRef filled) {
    StringBuilder text = new StringBuilder(DRAFT_NOTICE);
    text.append(" · Agent „").append(agentName).append("“, Fassung ").append(agentRevision);
    if (filled != null) {
      text.append(" · Vorlage „")
          .append(filled.name())
          .append("“, Fassung ")
          .append(filled.revision());
    }
    if (!libraries.isEmpty()) {
      text.append(" · Bibliotheken: ")
          .append(
              libraries.stream().map(SearchedLibraryRef::name).collect(Collectors.joining(", ")));
    }
    if (model != null && !model.isBlank()) {
      text.append(" · Modell ").append(model);
    }
    return text.toString();
  }

  /** A filled template by id, name at the time and the revision filled. */
  public record TemplateRef(UUID id, String name, int revision) {}
}
