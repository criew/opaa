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

export interface ResolveDroppedItemsResult {
  files: ResolvedDroppedFile[]
  /**
   * How many entries (individual files, or whole subtrees under an unreadable directory) could
   * not be read (#823 review, Befund 3) - a permission error, or a file removed/moved on disk
   * between the drop and this read, must not silently abort the rest of a large drop. Counted
   * here rather than thrown, so the caller can still upload everything that *did* resolve and
   * report the rest in one collective message.
   */
  failedCount: number
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

/**
 * Resolves every item of a drop event's `DataTransferItemList` - see this module's own header.
 * Never rejects on its own account of a single unreadable entry (see `collectEntry`/{@link
 * ResolveDroppedItemsResult.failedCount}) - a caller should still wrap the call itself in a
 * `.catch` for a genuinely unexpected failure (e.g. `webkitGetAsEntry` throwing outright on some
 * browser), which none of the per-entry handling below can guard against.
 */
export async function resolveDroppedItems(
  items: ArrayLike<DataTransferItemWithEntry>,
): Promise<ResolveDroppedItemsResult> {
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

  const files: ResolvedDroppedFile[] = []
  let failedCount = 0
  for (const entry of entries) {
    failedCount += await collectEntry(entry, '', files)
  }
  return { files, failedCount }
}

function fileEntryFallback(file: File): FileSystemFileEntryLike {
  return {
    isFile: true,
    isDirectory: false,
    name: file.name,
    file: (successCallback) => successCallback(file),
  }
}

/**
 * @returns how many entries under (and including) `entry` could not be read (#823 review, Befund
 *   3) - a single unreadable file, or a whole unreadable subdirectory, is counted and skipped
 *   rather than rejecting the promise chain this is part of, which would otherwise abort every
 *   sibling/later entry `resolveDroppedItems`'s own loop still has left to process.
 */
async function collectEntry(
  entry: FileSystemEntryLike,
  relativePath: string,
  out: ResolvedDroppedFile[],
): Promise<number> {
  if (entry.isFile) {
    try {
      const file = await entryToFile(entry as FileSystemFileEntryLike)
      out.push({ file, relativePath })
      return 0
    } catch {
      return 1
    }
  }
  if (entry.isDirectory) {
    const directoryPath = relativePath ? `${relativePath}/${entry.name}` : entry.name
    let children: FileSystemEntryLike[]
    try {
      children = await readAllEntries((entry as FileSystemDirectoryEntryLike).createReader())
    } catch {
      // The directory itself could not be listed at all (e.g. a permission error) - the whole
      // subtree counts as one failure rather than silently vanishing without a trace.
      return 1
    }
    let failedCount = 0
    for (const child of children) {
      failedCount += await collectEntry(child, directoryPath, out)
    }
    return failedCount
  }
  return 0
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

// #823 review, Befund 2: a real OS folder routinely carries files nobody dragged there on
// purpose - a browser's `accept` attribute is not reliably enforced for a `webkitdirectory`
// selection at all, and neither it nor a drop's `DataTransferItem.webkitGetAsEntry()` walk ever
// filters by format on their own. Treated as two different things: well-known OS/desktop metadata
// files are dropped silently (nobody dragged "Thumbs.db" into a document library on purpose, and
// naming it in a summary would only be noise), while every other unsupported format is counted and
// reported as one collective summary message - naming three hundred individual rejected files
// would be worse than naming none.
const SYSTEM_FILE_NAMES = new Set(['thumbs.db', '.ds_store', 'desktop.ini'])

/** Whether `fileName` is a well-known OS/desktop metadata file - see the constant above. */
export function isSystemFile(fileName: string): boolean {
  return SYSTEM_FILE_NAMES.has(fileName.toLowerCase())
}

/**
 * Splits `entries` into those whose file name matches one of `acceptedExtensions` (the same
 * comma-separated shape `LibraryDetailPage`'s own `ACCEPTED_FILE_EXTENSIONS`/the file input's
 * `accept` attribute already use) and a count of how many were skipped for not matching - a
 * well-known system file (see `isSystemFile`) is dropped silently and counted in neither list, the
 * same way it would be if a person had simply never dragged it in.
 */
export function filterAcceptedFiles<T extends { file: File }>(
  entries: T[],
  acceptedExtensions: string,
): { accepted: T[]; skippedCount: number } {
  const extensions = acceptedExtensions
    .split(',')
    .map((extension) => extension.trim().toLowerCase())
    .filter((extension) => extension.length > 0)
  const accepted: T[] = []
  let skippedCount = 0
  for (const entry of entries) {
    const lowerCasedName = entry.file.name.toLowerCase()
    if (isSystemFile(lowerCasedName)) {
      continue
    }
    if (extensions.some((extension) => lowerCasedName.endsWith(extension))) {
      accepted.push(entry)
    } else {
      skippedCount += 1
    }
  }
  return { accepted, skippedCount }
}
