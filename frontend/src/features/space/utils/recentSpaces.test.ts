import { beforeEach, describe, expect, it } from 'vitest'
import {
  MAX_RECENT_SPACES_IN_MENU,
  lastUsedSpace,
  recentSpaceIds,
  rememberSpaceUse,
  spacesByRecentUse,
} from './recentSpaces'

const STORAGE_KEY = 'opaa.spaces.recent'

function space(id: string) {
  return { id }
}

describe('recentSpaces', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('merkt sich die Nutzung, aktuellste zuerst', () => {
    rememberSpaceUse('a')
    rememberSpaceUse('b')
    rememberSpaceUse('c')

    expect(recentSpaceIds()).toEqual(['c', 'b', 'a'])
  })

  it('lässt einen erneut genutzten Space nach vorn rücken, statt ihn zu doppeln', () => {
    rememberSpaceUse('a')
    rememberSpaceUse('b')
    rememberSpaceUse('a')

    expect(recentSpaceIds()).toEqual(['a', 'b'])
  })

  it('merkt sich mehr Spaces, als das Menü zeigt', () => {
    for (let index = 0; index < 20; index++) rememberSpaceUse(`s${index}`)

    expect(recentSpaceIds().length).toBeGreaterThan(MAX_RECENT_SPACES_IN_MENU)
    expect(recentSpaceIds().length).toBeLessThanOrEqual(10)
  })

  it('liefert für unbrauchbaren Inhalt eine leere Liste statt eines Fehlers', () => {
    window.localStorage.setItem(STORAGE_KEY, 'kein JSON')
    expect(recentSpaceIds()).toEqual([])

    window.localStorage.setItem(STORAGE_KEY, '{"a":1}')
    expect(recentSpaceIds()).toEqual([])

    window.localStorage.setItem(STORAGE_KEY, '["a", 7, null, "b"]')
    expect(recentSpaceIds()).toEqual(['a', 'b'])
  })

  describe('lastUsedSpace', () => {
    it('findet den zuletzt genutzten Space der übergebenen Liste', () => {
      rememberSpaceUse('a')
      rememberSpaceUse('b')

      expect(lastUsedSpace([space('a'), space('b')])).toEqual(space('b'))
    })

    /** #1911: Der gemerkte Space ist nicht mehr zugänglich — der Aufrufer fällt zurück. */
    it('überspringt gemerkte Spaces, die der Dienst nicht mehr ausliefert', () => {
      rememberSpaceUse('persoenlich')
      rememberSpaceUse('entzogen')

      expect(lastUsedSpace([space('persoenlich')])).toEqual(space('persoenlich'))
    })

    it('liefert null, wenn kein gemerkter Space dabei ist', () => {
      rememberSpaceUse('entzogen')

      expect(lastUsedSpace([space('fremd')])).toBeNull()
      expect(lastUsedSpace([])).toBeNull()
    })
  })

  describe('spacesByRecentUse', () => {
    it('stellt die zuletzt genutzten Spaces in Nutzungsreihenfolge voran', () => {
      rememberSpaceUse('c')
      rememberSpaceUse('a')

      expect(spacesByRecentUse([space('a'), space('b'), space('c')]).map((s) => s.id)).toEqual([
        'a',
        'c',
        'b',
      ])
    })

    it('füllt ohne Nutzungsreihenfolge mit der übergebenen Reihenfolge auf', () => {
      expect(spacesByRecentUse([space('a'), space('b')]).map((s) => s.id)).toEqual(['a', 'b'])
    })

    it('zeigt höchstens fünf Einträge', () => {
      const many = Array.from({ length: 12 }, (_, index) => space(`s${index}`))
      rememberSpaceUse('s11')

      const shown = spacesByRecentUse(many)
      expect(shown).toHaveLength(MAX_RECENT_SPACES_IN_MENU)
      expect(shown[0]).toEqual(space('s11'))
    })

    it('nennt keinen Space doppelt', () => {
      rememberSpaceUse('a')
      rememberSpaceUse('a')

      const ids = spacesByRecentUse([space('a'), space('b')]).map((s) => s.id)
      expect(ids).toEqual(['a', 'b'])
    })
  })
})
