import { describe, expect, it } from 'vitest'
import {
  EMPTY_FILESYSTEM_VALUES,
  filesystemPayload,
  storedFilesystemValues,
  validateFilesystemValues,
} from './filesystemSource'

const valid = { ...EMPTY_FILESYSTEM_VALUES, sourcePath: '/data/dokumente' }

describe('filesystemSource (#2184)', () => {
  it('reads the stored patterns one per line and ignores anything that is no string', () => {
    expect(
      storedFilesystemValues({
        sourcePath: '/data/dokumente',
        sourceSettings: { excludePatterns: ['Archiv/**', 7, '**/*.tmp'] },
      }).excludePatterns,
    ).toBe('Archiv/**\n**/*.tmp')
    expect(storedFilesystemValues({ sourcePath: '/data' }).excludePatterns).toBe('')
  })

  it('always sends the patterns, so clearing them reaches the server as an empty list', () => {
    expect(filesystemPayload(valid).sourceSettings).toEqual({ excludePatterns: [] })
  })

  it('rejects absolute, duplicate, too long and too many patterns before sending', () => {
    expect(validateFilesystemValues({ ...valid, excludePatterns: 'Archiv/**' })).toBeNull()
    expect(validateFilesystemValues({ ...valid, excludePatterns: '/Archiv/**' })).toMatch(
      /relativ zum Verzeichnispfad/,
    )
    expect(validateFilesystemValues({ ...valid, excludePatterns: '*.tmp\n*.tmp' })).toMatch(
      /mehrfach/,
    )
    expect(validateFilesystemValues({ ...valid, excludePatterns: 'a'.repeat(256) })).toMatch(
      /255 Zeichen/,
    )
    expect(
      validateFilesystemValues({
        ...valid,
        excludePatterns: Array.from({ length: 51 }, (_, i) => `m${i}`).join('\n'),
      }),
    ).toMatch(/Höchstens 50/)
  })
})
