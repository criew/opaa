import { describe, expect, it } from 'vitest'
import { directoryPathFromWebkitRelativePath, resolveDroppedItems } from './directoryEntries'

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

    const resolved = await resolveDroppedItems(items)

    expect(resolved).toHaveLength(1)
    expect(resolved[0].file.name).toBe('bericht.pdf')
    expect(resolved[0].relativePath).toBe('')
  })

  it('recursively resolves a nested directory tree into files with their relative path', async () => {
    const yearFolder = fakeDirectoryEntry('2026', [[fakeFileEntry('januar.pdf')]])
    const protokolleFolder = fakeDirectoryEntry('Protokolle', [[yearFolder]])
    const items = [fakeItem(protokolleFolder)]

    const resolved = await resolveDroppedItems(items)

    expect(resolved).toHaveLength(1)
    expect(resolved[0].file.name).toBe('januar.pdf')
    expect(resolved[0].relativePath).toBe('Protokolle/2026')
  })

  it('calls readEntries repeatedly until it returns an empty batch', async () => {
    // #823: a directory whose entries arrive in two batches - the real regression this guards
    // against is a caller that only calls readEntries once and silently drops the second batch.
    const folder = fakeDirectoryEntry('Grossbestand', [
      [fakeFileEntry('a.pdf'), fakeFileEntry('b.pdf')],
      [fakeFileEntry('c.pdf')],
    ])
    const items = [fakeItem(folder)]

    const resolved = await resolveDroppedItems(items)

    expect(resolved.map((r) => r.file.name).sort()).toEqual(['a.pdf', 'b.pdf', 'c.pdf'])
    expect(resolved.every((r) => r.relativePath === 'Grossbestand')).toBe(true)
  })

  it('mixes files and folders dropped together in a single drop', async () => {
    const rootFile = fakeFileEntry('anschreiben.pdf')
    const folder = fakeDirectoryEntry('Anlagen', [[fakeFileEntry('anlage1.pdf')]])
    const items = [fakeItem(rootFile), fakeItem(folder)]

    const resolved = await resolveDroppedItems(items)

    expect(resolved).toHaveLength(2)
    const byName = new Map(resolved.map((r) => [r.file.name, r.relativePath]))
    expect(byName.get('anschreiben.pdf')).toBe('')
    expect(byName.get('anlage1.pdf')).toBe('Anlagen')
  })

  it('falls back to getAsFile when webkitGetAsEntry is unavailable', async () => {
    const file = new File(['x'], 'ohne-entry-api.txt')
    const items = [{ kind: 'file', getAsFile: () => file } as DroppedItem]

    const resolved = await resolveDroppedItems(items)

    expect(resolved).toEqual([{ file, relativePath: '' }])
  })

  it('ignores a non-file drag item (e.g. dragged text)', async () => {
    const items = [
      { kind: 'string', webkitGetAsEntry: () => null, getAsFile: () => null } as DroppedItem,
    ]

    const resolved = await resolveDroppedItems(items)

    expect(resolved).toEqual([])
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
