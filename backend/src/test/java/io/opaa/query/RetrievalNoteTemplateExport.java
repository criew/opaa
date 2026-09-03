package io.opaa.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The retrieval stages' German-translation surface - {@link RetrievalNote}, {@link
 * RetrievalListLabel}, and {@link StageStatus}'s not-run notes - exported as a stable, sorted
 * structure and committed as JSON at {@code frontend/src/utils/retrieval-note-templates.json}.
 * {@code RetrievalNoteTest} compares {@link #current()} against {@link #committed()} on every
 * backend run; {@code retrievalProtocolText.test.ts} reads the same file to check its own
 * translation inventory for coverage - the mechanically shared source of truth #1207 introduces in
 * place of three hand-maintained copies of the same set.
 */
final class RetrievalNoteTemplateExport {

  record Entry(String name, String template) {}

  record Export(List<Entry> notes, List<Entry> listLabels) {}

  private RetrievalNoteTemplateExport() {}

  static Export current() {
    List<Entry> notes = new ArrayList<>();
    for (RetrievalNote note : RetrievalNote.values()) {
      notes.add(new Entry(note.name(), note.template()));
    }
    for (StageStatus status : StageStatus.values()) {
      // EXECUTED's own note never reaches the protocol, see RetrievalNoteTest.
      if (status != StageStatus.EXECUTED) {
        notes.add(new Entry(status.name(), status.note()));
      }
    }
    notes.sort(Comparator.comparing(Entry::name));

    List<Entry> listLabels = new ArrayList<>();
    for (RetrievalListLabel label : RetrievalListLabel.values()) {
      listLabels.add(new Entry(label.name(), label.template()));
    }
    listLabels.sort(Comparator.comparing(Entry::name));

    return new Export(notes, listLabels);
  }

  static Export committed() {
    try {
      return new ObjectMapper().readValue(frontendTemplatesFile().toFile(), Export.class);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + frontendTemplatesFile(), e);
    }
  }

  /** Overwrites the committed file with {@link #current()} - the regeneration path for #1207. */
  static void regenerate() {
    try {
      new ObjectMapper()
          .writerWithDefaultPrettyPrinter()
          .writeValue(frontendTemplatesFile().toFile(), current());
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write " + frontendTemplatesFile(), e);
    }
  }

  private static Path frontendTemplatesFile() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
      Path candidate = dir.resolve("frontend").resolve("src").resolve("utils");
      if (Files.isDirectory(candidate)) {
        return candidate.resolve("retrieval-note-templates.json");
      }
    }
    throw new UncheckedIOException(
        new IOException(
            "Could not locate frontend/src/utils by walking up from "
                + System.getProperty("user.dir")
                + ". Run this test from within the repository."));
  }
}
