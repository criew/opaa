package io.opaa.indexing;

/**
 * Thrown by {@link FileProcessingService#processUploadedFile} when Tika extracts no text at all
 * from an uploaded file (#420 code review, nit 6). Unlike the directory/URL ingestion paths, whose
 * job-based reporting model has a place for a listed {@code FAILED} row with dead {@code file_path}
 * ({@code AsyncIndexingExecutor}/{@code UrlIndexingExecutor} count it as a processed document in a
 * batch), the interactive upload endpoint has no such use for one: nothing is gained by returning
 * {@code 201 Created} for a document with no retrievable content and a file that outlives it.
 * {@code io.opaa.library.LibraryDocumentService} catches this and answers {@code 422} instead,
 * after removing the file it already stored.
 */
public class EmptyDocumentContentException extends RuntimeException {

  public EmptyDocumentContentException(String fileName) {
    super("No content could be extracted from uploaded file: " + fileName);
  }
}
