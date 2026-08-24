// #823 (Epic #520 Phase 4): recursively resolves a drag-and-drop DataTransferItemList into
// individual files with their relative path within the dropped structure - lets LibraryDetailPage
// upload a whole dragged-and-dropped folder tree one file at a time while preserving the structure
// it was dropped with (uploaded via each file's own `folderPath`, materialized idempotently by the
// backend - see LibraryDocumentService#uploadDocument).
//
// Uses the non-standard but universally supported `DataTransferItem.webkitGetAsEntry()` API rather
// than the plain `DataTransferItem.getAsFile()` every other upload path already uses, because a
// directory entry has no File representation of its own: only walking
// `FileSystemDirectoryEntry.createReader()` uncovers the files inside it.
//
// `webkitGetAsEntry()` itself must be called synchronously, before any `await` - browsers only
// keep a drop event's DataTransferItemList valid for the duration of the synchronous event
// handler, and calling it after this module's own internal `await`s would silently return `null`
// for every item. `resolveDroppedItems` therefore collects every entry up front (a plain,
// synchronous loop) before its own recursive resolution ever awaits anything.

export interface ResolvedDroppedFile {
  file: File
  /**
   * Path within the dropped structure, "/"-separated, not including the file's own name - e.g.
   * "Protokolle/2026" for a file dropped as part of "Protokolle/2026/Januar.pdf", or "" for a
   * plain file dropped directly (no enclosing directory at all).
   */
  relativePath: string
}

interface FileSystemEntryLike {
  isFile: boolean
  isDirectory: boolean
  name: string
}

interface FileSystemFileEntryLike extends FileSystemEntryLike {
  file(successCallback: (file: File) => void, errorCallback?: (error: unknown) => void): void
}

interface FileSystemDirectoryReaderLike {
  readEntries(
    successCallback: (entries: FileSystemEntryLike[]) => void,
    errorCallback?: (error: unknown) => void,
  ): void
}

interface FileSystemDirectoryEntryLike extends FileSystemEntryLike {
  createReader(): FileSystemDirectoryReaderLike
}

interface DataTransferItemWithEntry {
  kind: string
  webkitGetAsEntry?: () => FileSystemEntryLike | null
  getAsFile: () => File | null
}

/** Resolves every item of a drop event's `DataTransferItemList` - see this module's own header. */
export async function resolveDroppedItems(
  items: ArrayLike<DataTransferItemWithEntry>,
): Promise<ResolvedDroppedFile[]> {
  const entries: FileSystemEntryLike[] = []
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    if (item.kind !== 'file') continue
    const entry = item.webkitGetAsEntry?.() ?? null
    if (entry) {
      entries.push(entry)
      continue
    }
    // A browser without webkitGetAsEntry support (or an item it returns null for, e.g. a plain
    // file whose entry API is unavailable) falls back to the File the item also carries directly,
    // treated as a root-level file with no enclosing directory - the same shape a plain (non-
    // folder) drop already produces.
    const file = item.getAsFile()
    if (file) {
      entries.push(fileEntryFallback(file))
    }
  }

  const resolved: ResolvedDroppedFile[] = []
  for (const entry of entries) {
    await collectEntry(entry, '', resolved)
  }
  return resolved
}

function fileEntryFallback(file: File): FileSystemFileEntryLike {
  return {
    isFile: true,
    isDirectory: false,
    name: file.name,
    file: (successCallback) => successCallback(file),
  }
}

async function collectEntry(
  entry: FileSystemEntryLike,
  relativePath: string,
  out: ResolvedDroppedFile[],
): Promise<void> {
  if (entry.isFile) {
    const file = await entryToFile(entry as FileSystemFileEntryLike)
    out.push({ file, relativePath })
    return
  }
  if (entry.isDirectory) {
    const directoryPath = relativePath ? `${relativePath}/${entry.name}` : entry.name
    const children = await readAllEntries((entry as FileSystemDirectoryEntryLike).createReader())
    for (const child of children) {
      await collectEntry(child, directoryPath, out)
    }
  }
}

function entryToFile(entry: FileSystemFileEntryLike): Promise<File> {
  return new Promise((resolve, reject) => entry.file(resolve, reject))
}

/**
 * `readEntries` only ever returns one batch at a time (browser-dependent, commonly ~100 entries) -
 * a directory with more entries than that requires calling it again, repeatedly, until it finally
 * answers with an empty array. Easy to miss in testing since a small directory never triggers a
 * second batch, but a real Aktenordner with hundreds of files would otherwise silently lose
 * everything past the first batch.
 */
function readAllEntries(reader: FileSystemDirectoryReaderLike): Promise<FileSystemEntryLike[]> {
  return new Promise((resolve, reject) => {
    const all: FileSystemEntryLike[] = []
    const readBatch = () => {
      reader.readEntries((batch) => {
        if (batch.length === 0) {
          resolve(all)
          return
        }
        all.push(...batch)
        readBatch()
      }, reject)
    }
    readBatch()
  })
}

/**
 * The `webkitdirectory` file-input counterpart to `resolveDroppedItems` above (#823): a file
 * selected that way carries its full path within the selected directory as `webkitRelativePath`
 * (e.g. "Protokolle/2026/Januar.pdf") - this strips the file's own name off the end, leaving just
 * the directory portion ("Protokolle/2026"), the same shape `ResolvedDroppedFile.relativePath`
 * already has. Returns "" for a bare file name with no directory portion at all (should not happen
 * for a `webkitdirectory` selection, but a defensive fallback rather than a leading/trailing "/").
 */
export function directoryPathFromWebkitRelativePath(webkitRelativePath: string): string {
  const lastSlash = webkitRelativePath.lastIndexOf('/')
  return lastSlash === -1 ? '' : webkitRelativePath.slice(0, lastSlash)
}
