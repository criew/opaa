package io.opaa.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A prepared chat transcript for {@link ChatImportService}: the title and the turns in order. */
public record ChatImport(String title, List<Turn> turns) {

  public ChatImport {
    turns = List.copyOf(turns);
  }

  /** One question with its answer; {@code sources} lists the answer's documents in rank order. */
  public record Turn(
      String question, String answer, Instant askedAt, Instant answeredAt, List<Source> sources) {

    public Turn {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }

  /** A document behind an answer, and whether the answer text cites it. */
  public record Source(UUID documentId, boolean cited) {}
}
