import { useEffect, useRef, useState } from 'react'
import type { DragEvent } from 'react'
import { useDocumentStore } from '../stores/documentStore'
import { filterAcceptedFiles, resolveDroppedItems } from '../utils/directoryEntries'

// Mirrors what the registered DocumentFormats admit (DocumentFormat#admittedFormats,
// backend/src/main/java/io/opaa/format) - only a client-side hint for the file picker;
// the backend remains the authority on what is accepted.
// Exported so directoryEntries.test.ts exercises filterAcceptedFiles against the same list rather
// than a copy that can silently drift out of sync with it.
export const ACCEPTED_FILE_EXTENSIONS =
  '.csv,.doc,.docx,.eml,.html,.md,.msg,.odp,.ods,.odt,.pdf,.pptx,.txt,.xlsx'

/** The directory portion within a dropped/selected folder tree, e.g. "Protokolle/2026". */
interface UploadEntry {
  file: File
  relativePath?: string
}

/**
 * The upload of an UPLOAD library: the file and folder pickers, drag and drop, the client-side
 * format filter and the per-file upload loop. Every upload that went through calls
 * onDocumentsChanged; a failed file is collected in documentStore.uploadErrors and does not stop
 * the others.
 */
export function useDocumentUpload(
  libraryId: string,
  canManage: boolean,
  onDocumentsChanged: () => void,
) {
  const isUploading = useDocumentStore((s) => s.isUploading)
  const uploadWaitSeconds = useDocumentStore((s) => s.uploadWaitSeconds)
  const uploadNewDocument = useDocumentStore((s) => s.uploadNewDocument)
  const reportUploadError = useDocumentStore((s) => s.reportUploadError)
  const clearUploadErrors = useDocumentStore((s) => s.clearUploadErrors)

  const [isDragActive, setIsDragActive] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  // A whole-folder counterpart to fileInputRef - `webkitdirectory` is not part of React's
  // JSX typings for <input>, so it is set imperatively via the effect below instead of as a prop.
  const folderInputRef = useRef<HTMLInputElement>(null)
  // Ends a running wait for a 429 once the page leaves this library; files not yet sent still go
  // out.
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    abortRef.current = controller
    return () => controller.abort()
  }, [libraryId])

  useEffect(() => {
    // `webkitdirectory` (plus its older aliases) is not part of React's <input> typings, so
    // it has to be set on the DOM node directly rather than passed as a JSX prop.
    const node = folderInputRef.current
    if (!node) return
    node.setAttribute('webkitdirectory', '')
    node.setAttribute('directory', '')
    node.setAttribute('mozdirectory', '')
  }, [])

  async function handleFiles(entries: UploadEntry[], options?: { skipClear?: boolean }) {
    if (!canManage) return
    // The folder-upload entry points below already clear uploadErrors
    // themselves, right before adding their own skipped-files summary.
    if (!options?.skipClear) {
      clearUploadErrors()
    }
    const signal = abortRef.current?.signal
    for (const entry of entries) {
      try {
        await uploadNewDocument(libraryId, entry.file, entry.relativePath || undefined, signal)
        onDocumentsChanged()
      } catch {
        // The error is already collected in documentStore.uploadErrors; the loop keeps
        // going, so that an error on one file does not block the others.
      }
    }
  }

  // One collective German message for every file skipped client-side before
  // ever reaching the backend - naming three hundred rejected files would be worse than none.
  function reportSkippedFiles(skippedCount: number, failedCount: number) {
    const parts: string[] = []
    if (skippedCount > 0) {
      parts.push(
        `${skippedCount} ${skippedCount === 1 ? 'Datei wurde' : 'Dateien wurden'} wegen eines nicht unterstützten Formats übersprungen`,
      )
    }
    if (failedCount > 0) {
      parts.push(
        `${failedCount} ${failedCount === 1 ? 'Datei konnte' : 'Dateien konnten'} nicht gelesen werden`,
      )
    }
    if (parts.length > 0) {
      reportUploadError(`${parts.join('; ')}.`)
    }
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setIsDragActive(false)
    clearUploadErrors()
    // DataTransferItemList.webkitGetAsEntry() must be read synchronously, before any await -
    // the items list has to be captured here, inside this synchronous handler.
    const items = event.dataTransfer.items
    if (items && items.length > 0) {
      resolveDroppedItems(items)
        .then(({ files, failedCount }) => {
          const { accepted, skippedCount } = filterAcceptedFiles(files, ACCEPTED_FILE_EXTENSIONS)
          reportSkippedFiles(skippedCount, failedCount)
          if (accepted.length > 0) {
            void handleFiles(
              accepted.map((r) => ({ file: r.file, relativePath: r.relativePath || undefined })),
              { skipClear: true },
            )
          }
        })
        .catch(() => {
          reportUploadError('Der abgelegte Ordner konnte nicht gelesen werden.')
        })
      return
    }
    if (event.dataTransfer.files.length > 0) {
      const entries = Array.from(event.dataTransfer.files).map((file) => ({ file }))
      const { accepted, skippedCount } = filterAcceptedFiles(entries, ACCEPTED_FILE_EXTENSIONS)
      reportSkippedFiles(skippedCount, 0)
      if (accepted.length > 0) {
        void handleFiles(accepted, { skipClear: true })
      }
    }
  }

  return {
    isUploading,
    uploadWaitSeconds,
    isDragActive,
    setIsDragActive,
    fileInputRef,
    folderInputRef,
    clearUploadErrors,
    handleFiles,
    reportSkippedFiles,
    handleDrop,
  }
}

export type DocumentUpload = ReturnType<typeof useDocumentUpload>
