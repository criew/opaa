package io.opaa.indexing.source.googledrive;

import io.opaa.common.ByteSizes;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FileSyncWording;

/** The protocol sentences of a Google Drive run in its own terms. */
final class GoogleDriveWording implements FileSyncWording {

  @Override
  public String tooLarge(FileEntry entry, long maxBytes) {
    return "„" + entry.fileName() + "“ ist größer als " + ByteSizes.format(maxBytes) + ".";
  }

  @Override
  public String tooManyEntries(long maxEntries) {
    return "Die Geltungsbereiche dieser Bibliothek listen mehr als "
        + maxEntries
        + " Dateien; so viele verarbeitet ein Lauf nicht. Bitte die Geltungsbereiche enger fassen"
        + " oder die Bibliothek aufteilen.";
  }

  @Override
  public String goneConfirmed() {
    return "In Google Drive gelöscht oder aus den Geltungsbereichen entfernt";
  }

  @Override
  public String droppedReferencesNote() {
    return " gemeldete Dateien liegen außerhalb der Geltungsbereiche und wurden verworfen";
  }

  @Override
  public String budgetStallAdvice() {
    return "Der Lauf hat keine Datei neu aufgenommen. Budget anheben oder die Geltungsbereiche"
        + " aufteilen.";
  }

  @Override
  public String eventRunContinuation() {
    return "der nächste Lauf liest das Änderungsprotokoll ab derselben Stelle erneut";
  }

  @Override
  public String listedSummary(long listed, long deselected) {
    return listed + " Dateien gelistet, ";
  }

  @Override
  public String checkedSummary(long checked) {
    return checked + " gemeldete Änderungen geprüft, ";
  }
}
