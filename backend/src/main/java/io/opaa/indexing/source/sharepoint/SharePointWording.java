package io.opaa.indexing.source.sharepoint;

import io.opaa.common.ByteSizes;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FileSyncWording;

/** The protocol sentences of a SharePoint run in its own terms. */
final class SharePointWording implements FileSyncWording {

  @Override
  public String tooLarge(FileEntry entry, long maxBytes) {
    return "„" + entry.fileName() + "“ ist größer als " + ByteSizes.format(maxBytes) + ".";
  }

  @Override
  public String tooManyEntries(long maxEntries) {
    return "Die Dokumentbibliotheken dieser Bibliothek listen mehr als "
        + maxEntries
        + " Dateien; so viele verarbeitet ein Lauf nicht. Bitte Ordner wählen oder die Bibliothek"
        + " aufteilen.";
  }

  @Override
  public String goneConfirmed() {
    return "In SharePoint gelöscht oder aus den gewählten Ordnern entfernt";
  }

  @Override
  public String droppedReferencesNote() {
    return " gemeldete Dateien liegen außerhalb der Dokumentbibliotheken und wurden verworfen";
  }

  @Override
  public String budgetStallAdvice() {
    return "Der Lauf hat keine Datei neu aufgenommen. Budget anheben oder die Bibliothek"
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
