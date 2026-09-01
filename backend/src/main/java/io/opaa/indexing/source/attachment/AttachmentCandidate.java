package io.opaa.indexing.source.attachment;

import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.SupportedDocumentFormats;

/**
 * A single attachment link an {@link AttachmentProfile} found on an RSS entry's detail page.
 *
 * @param url the absolute URL the attachment is downloaded from - also its identity for
 *     deduplication via {@link DocumentRepository#findByLibraryIdAndFilePath}: the same attachment
 *     linked from two entries in the same library becomes one document, exactly the way {@link
 *     FileProcessingService#processUrlFile} already deduplicates by {@code (library_id, file_path)}
 *     for {@code HTTP_DIRECTORY} documents.
 * @param suggestedFileName a best-effort file name for the attachment. For {@link
 *     AttachmentProfile#GENERIC} this is simply the URL's last path segment, already carrying a
 *     supported extension - that is what qualified the link as an attachment in the first place.
 *     For {@link AttachmentProfile#GSB} the URL itself carries no extension (the file is served
 *     through a query parameter instead), so this name may still lack one; the caller resolves that
 *     gap from the response's {@code Content-Type} once the attachment is actually downloaded (see
 *     {@link SupportedDocumentFormats#extensionForContentType}).
 */
public record AttachmentCandidate(String url, String suggestedFileName) {}
