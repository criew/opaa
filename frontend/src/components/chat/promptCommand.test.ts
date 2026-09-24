import { describe, expect, it } from 'vitest'
import type { AvailablePrompt } from '../../types/api'
import { findActiveSlashCommand, matchPrompts } from './promptCommand'

function available(name: string, title: string, description?: string): AvailablePrompt {
  return {
    id: name,
    libraryId: 'library',
    libraryName: 'Bibliothek',
    name,
    title,
    description: description ?? null,
    hasVariables: false,
    associatedWithSpace: false,
  }
}

describe('findActiveSlashCommand', () => {
  it('opens only for a slash at the start of a line', () => {
    expect(findActiveSlashCommand('/zusam', 6)).toEqual({ start: 0, query: 'zusam' })
    expect(findActiveSlashCommand('Erste Zeile\n/an', 15)).toEqual({ start: 12, query: 'an' })
    expect(findActiveSlashCommand('siehe /etc', 10)).toBeNull()
    expect(findActiveSlashCommand('/zusam fassen', 13)).toBeNull()
  })
})

describe('matchPrompts', () => {
  const prompts = [
    available('zusammenfassung', 'Zusammenfassung'),
    available('anhoerung', 'Anhörungsschreiben', 'Entwurf nach VwVfG'),
  ]

  it('searches command name, title and description, keeping the order', () => {
    expect(matchPrompts(prompts, 'zusam').map((p) => p.name)).toEqual(['zusammenfassung'])
    expect(matchPrompts(prompts, 'schreiben').map((p) => p.name)).toEqual(['anhoerung'])
    expect(matchPrompts(prompts, 'vwvfg').map((p) => p.name)).toEqual(['anhoerung'])
    expect(matchPrompts(prompts, '')).toHaveLength(2)
  })
})
