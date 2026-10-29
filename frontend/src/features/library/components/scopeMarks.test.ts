import { describe, expect, it } from 'vitest'
import { unreadableScopeNames } from './scopeMarks'

describe('unreadableScopeNames for Google Drive (#3144)', () => {
  const library = (scopes: unknown[]) => ({
    sourceType: 'GOOGLE_DRIVE' as const,
    sourceSettings: { scopes },
  })

  it('names "Meine Ablage" by itself, not by the shared drive listed before it', () => {
    expect(
      unreadableScopeNames(library([{ drive: '0Abc', name: 'Projektablage' }, { myDrive: true }]), [
        'myDrive',
      ]),
    ).toEqual(['Meine Ablage'])
  })

  it('names a scope with a blank name by a short stand-in, never by its key', () => {
    expect(
      unreadableScopeNames(
        library([
          { drive: '0Abc', name: '' },
          { folder: '1Xyz', name: ' ' },
        ]),
        ['drive:0Abc', 'folder:1Xyz'],
      ),
    ).toEqual(['unbekannte Ablage', 'unbekannter Ordner'])
  })
})
