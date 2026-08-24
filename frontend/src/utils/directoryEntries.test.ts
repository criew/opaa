import { describe, expect, it } from 'vitest'
import {
  directoryPathFromWebkitRelativePath,
  filterAcceptedFiles,
  isSystemFile,
  resolveDroppedItems,
} from './directoryEntries'

// #823: fake DataTransferItem/FileSystemEntry stand-ins - jsdom does not implement
// webkitGetAsEntry()/FileSystemDirectoryEntry at all, so these tests build the minimal shape
// resolveDroppedItems actually consumes (kind/webkitGetAsEntry/getAsFile, isFile/isDirectory/
// name/file()/createReader()). resolveDroppedItems's own item type is intentionally internal (not
// part of the module's public surface) - DroppedItem below extracts it structurally instead of
// exporting it just for this test file to reference.
type DroppedItem = Parameters<typeof resolveDroppedItems>[0] extends ArrayLike<infer I> ? I : never

function fakeFileEntry(name: string, content = 'content'): unknown {
  return {
    isFile: true,
    isDirectory: false,
    name,
    file: (successCallback: (file: File) => void) => successCallback(new File([content], name)),
  }
}

// #823 review, Befund 3: a file entry whose file() callback always errors - stands in for a
// permission error or a file removed/moved between the drop and this read.
function fakeUnreadableFileEntry(name: string): unknown {
  return {
    isFile: true,
    isDirectory: false,
    name,
    file: (_successCallback: (file: File) => void, errorCallback?: (error: unknown) => void) =>
      errorCallback?.(new Error('NotReadableError')),
  }
}

// #823: exercises readAllEntries' repeated-readEntries loop explicitly - readEntries only ever
// returns one batch at a time in a real browser, and a directory handled by only calling it once
// would silently lose every entry past the first batch.
function fakeDirectoryEntry(name: string, entryBatches: unknown[][]): unknown {
  return {
    isFile: false,
    isDirectory: true,
    name,
    createReader: () => {
      let batchIndex = 0
      return {
        readEntries: (callback: (entries: unknown[]) => void) => {
          const batch = entryBatches[batchIndex] ?? []
          batchIndex += 1
          callback(batch)
        },
      }
    },
  }
}

// #823 review, Befund 3: a directory whose createReader/readEntries always errors - stands in for
// a directory that cannot be listed at all (e.g. a permission error on the directory itself).
function fakeUnreadableDirectoryEntry(name: string): unknown {
  return {
    isFile: false,
    isDirectory: true,
    name,
    createReader: () => ({
      readEntries: (
        _callback: (entries: unknown[]) => void,
        errorCallback?: (error: unknown) => void,
      ) => errorCallback?.(new Error('NotReadableError')),
    }),
  }
}

function fakeItem(entry: unknown): DroppedItem {
  return {
    kind: 'file',
    webkitGetAsEntry: () => entry,
    getAsFile: () => null,
  } as DroppedItem
}

describe('resolveDroppedItems', () => {
  it('resolves a plain file dropped directly with an empty relativePath', async () => {
    const items = [fakeItem(fakeFileEntry('bericht.pdf'))]

    const { files, failedCount } = await resolveDroppedItems(items)

    expect(files).toHaveLength(1)
    expect(files[0].file.name).toBe('bericht.pdf')
    expect(files[0].relativePath).toBe('')
    expect(failedCount).toBe(0)
  })

  it('recursively resolves a nested directory tree into files with their relative path', async () => {
    const yearFolder = fakeDirectoryEntry('2026', [[fakeFileEntry('januar.pdf')]])
    const protokolleFolder = fakeDirectoryEntry('Protokolle', [[yearFolder]])
    const items = [fakeItem(protokolleFolder)]

    const { files, failedCount } = await resolveDroppedItems(items)

    expect(files).toHaveLength(1)
    expect(files[0].file.name).toBe('januar.pdf')
    expect(files[0].relativePath).toBe('Protokolle/2026')
    expect(failedCount).toBe(0)
  })

  it('calls readEntries repeatedly until it returns an empty batch', async () => {
    // #823: a directory whose entries arrive in two batches - the real regression this guards
    // against is a caller that only calls readEntries once and silently drops the second batch.
    const folder = fakeDirectoryEntry('Grossbestand', [
      [fakeFileEntry('a.pdf'), fakeFileEntry('b.pdf')],
      [fakeFileEntry('c.pdf')],
    ])
    const items = [fakeItem(folder)]

    const { files } = await resolveDroppedItems(items)

    expect(files.map((r) => r.file.name).sort()).toEqual(['a.pdf', 'b.pdf', 'c.pdf'])
    expect(files.every((r) => r.relativePath === 'Grossbestand')).toBe(true)
  })

  it('mixes files and folders dropped together in a single drop', async () => {
    const rootFile = fakeFileEntry('anschreiben.pdf')
    const folder = fakeDirectoryEntry('Anlagen', [[fakeFileEntry('anlage1.pdf')]])
    const items = [fakeItem(rootFile), fakeItem(folder)]

    const { files } = await resolveDroppedItems(items)

    expect(files).toHaveLength(2)
    const byName = new Map(files.map((r) => [r.file.name, r.relativePath]))
    expect(byName.get('anschreiben.pdf')).toBe('')
    expect(byName.get('anlage1.pdf')).toBe('Anlagen')
  })

  it('falls back to getAsFile when webkitGetAsEntry is unavailable', async () => {
    const file = new File(['x'], 'ohne-entry-api.txt')
    const items = [{ kind: 'file', getAsFile: () => file } as DroppedItem]

    const { files } = await resolveDroppedItems(items)

    expect(files).toEqual([{ file, relativePath: '' }])
  })

  it('ignores a non-file drag item (e.g. dragged text)', async () => {
    const items = [
      { kind: 'string', webkitGetAsEntry: () => null, getAsFile: () => null } as DroppedItem,
    ]

    const { files } = await resolveDroppedItems(items)

    expect(files).toEqual([])
  })

  // #823 review, Befund 3: an unreadable file/directory must not abort the whole drop.
  it('counts an unreadable file instead of aborting the rest of the drop', async () => {
    const items = [
      fakeItem(fakeUnreadableFileEntry('kaputt.pdf')),
      fakeItem(fakeFileEntry('gut.pdf')),
    ]

    const { files, failedCount } = await resolveDroppedItems(items)

    expect(files.map((r) => r.file.name)).toEqual(['gut.pdf'])
    expect(failedCount).toBe(1)
  })

  it('counts an unreadable subdirectory instead of aborting sibling entries', async () => {
    const items = [
      fakeItem(fakeUnreadableDirectoryEntry('Gesperrt')),
      fakeItem(fakeFileEntry('gut.pdf')),
    ]

    const { files, failedCount } = await resolveDroppedItems(items)

    expect(files.map((r) => r.file.name)).toEqual(['gut.pdf'])
    expect(failedCount).toBe(1)
  })

  it('counts an unreadable file nested deep inside an otherwise readable tree', async () => {
    const yearFolder = fakeDirectoryEntry('2026', [
      [fakeUnreadableFileEntry('kaputt.pdf'), fakeFileEntry('gut.pdf')],
    ])
    const protokolleFolder = fakeDirectoryEntry('Protokolle', [[yearFolder]])
    const items = [fakeItem(protokolleFolder)]

    const { files, failedCount } = await resolveDroppedItems(items)

    expect(files.map((r) => r.file.name)).toEqual(['gut.pdf'])
    expect(failedCount).toBe(1)
  })
})

describe('directoryPathFromWebkitRelativePath', () => {
  it('strips the file name, keeping only the directory portion', () => {
    expect(directoryPathFromWebkitRelativePath('Protokolle/2026/Januar.pdf')).toBe(
      'Protokolle/2026',
    )
  })

  it('returns an empty string for a bare file name with no directory portion', () => {
    expect(directoryPathFromWebkitRelativePath('Januar.pdf')).toBe('')
  })

  it('returns the single enclosing folder for a one-level-deep selection', () => {
    expect(directoryPathFromWebkitRelativePath('Protokolle/Januar.pdf')).toBe('Protokolle')
  })
})

// #823 review, Befund 2: format filtering for the folder upload path - a real OS folder routinely
// carries files nobody dragged there on purpose.
describe('filterAcceptedFiles', () => {
  const acceptedExtensions = '.doc,.docx,.md,.pdf,.pptx,.txt'

  it('accepts files matching one of the given extensions', () => {
    const entries = [{ file: new File(['x'], 'bericht.pdf') }]

    const { accepted, skippedCount } = filterAcceptedFiles(entries, acceptedExtensions)

    expect(accepted).toEqual(entries)
    expect(skippedCount).toBe(0)
  })

  it('counts a file with an unsupported extension as skipped', () => {
    const entries = [{ file: new File(['x'], 'bild.jpg') }]

    const { accepted, skippedCount } = filterAcceptedFiles(entries, acceptedExtensions)

    expect(accepted).toEqual([])
    expect(skippedCount).toBe(1)
  })

  it('drops a well-known system file silently, without counting it as skipped', () => {
    const entries = [
      { file: new File(['x'], 'Thumbs.db') },
      { file: new File(['x'], '.DS_Store') },
      { file: new File(['x'], 'desktop.ini') },
      { file: new File(['x'], 'bericht.pdf') },
    ]

    const { accepted, skippedCount } = filterAcceptedFiles(entries, acceptedExtensions)

    expect(accepted.map((e) => e.file.name)).toEqual(['bericht.pdf'])
    expect(skippedCount).toBe(0)
  })

  it('matches extensions case-insensitively', () => {
    const entries = [{ file: new File(['x'], 'BERICHT.PDF') }]

    const { accepted } = filterAcceptedFiles(entries, acceptedExtensions)

    expect(accepted).toHaveLength(1)
  })
})

describe('isSystemFile', () => {
  it('recognises Thumbs.db/.DS_Store/desktop.ini regardless of case', () => {
    expect(isSystemFile('Thumbs.db')).toBe(true)
    expect(isSystemFile('thumbs.db')).toBe(true)
    expect(isSystemFile('.DS_Store')).toBe(true)
    expect(isSystemFile('Desktop.ini')).toBe(true)
  })

  it('does not treat an ordinary document as a system file', () => {
    expect(isSystemFile('bericht.pdf')).toBe(false)
  })
})
