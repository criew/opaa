import { describe, expect, it } from 'vitest'
import { providerInitials } from './providerInitials'

describe('providerInitials', () => {
  it.each([
    ['Verzeichnisdienst', 'V'],
    ['Landes Portal', 'LP'],
    ['Stadtverwaltung (Keycloak)', 'SK'],
    ['  entra id  ', 'EI'],
    ['Portal – Nord Süd', 'PN'],
    ['', '?'],
    ['()', '?'],
  ])('reads „%s“ as %s', (name, initials) => {
    expect(providerInitials(name)).toBe(initials)
  })
})
