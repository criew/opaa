package io.opaa.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The one configurable value of the Gesprächsnotiz (docs/handbuch/suche.md, Abschnitt 10.3). Every
 * other quantity of the note - two points per turn, 200 characters, the display threshold of three
 * turns, the kind of a point, the model - is a fixed value with no addressee to reconfigure it
 * (docs/features/conversation-memory.md, "Konfiguration: Ebenenzuordnung").
 *
 * <p>Under {@code opaa.chat.*} rather than {@code opaa.query.*}: the note is chat content with the
 * chat's lifecycle, not a retrieval parameter, and this package must not depend on {@code
 * io.opaa.query}, which depends on it.
 *
 * @param maxItems how many points a chat's note holds at most; appending beyond it drops the oldest
 *     point, without a model call that rewrites the existing list. Default 10, at least 1, at most
 *     50. Its effect is only visible with the multi-turn {@code constraint_carryover} cases.
 */
@ConfigurationProperties(prefix = "opaa.chat.note")
public record ChatNoteProperties(@DefaultValue("10") int maxItems) {

  public ChatNoteProperties {
    if (maxItems < 1 || maxItems > 50) {
      throw new IllegalArgumentException("maxItems must be between 1 and 50, got " + maxItems);
    }
  }
}
