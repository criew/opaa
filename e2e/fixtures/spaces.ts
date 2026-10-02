import { expect } from '@playwright/test'
import { apiAs } from './externalAccess'

/**
 * Space-Zuordnungen als Vorbedingung: Ein Space durchsucht im Chat nur das ihm zugeordnete
 * Wissen und bietet nur zugeordnete Prompts an. Szenarien, deren Gegenstand die Suche oder das
 * Einsetzen ist, ordnen ihre Bibliothek deshalb vorher über die API zu - im Namen des Kontos, das
 * im Space chattet und dort als Administrator zuordnen darf.
 */

type AssetType = 'KNOWLEDGE_LIBRARY' | 'PROMPT_LIBRARY'

/** Die Kennung des Standard-Space von `devUser` - der Space, in dem `startFreshChat` landet. */
export async function defaultSpaceIdOf(devUser: string): Promise<string> {
  const api = await apiAs(devUser)
  try {
    const response = await api.get('/api/v1/spaces')
    expect(response.status(), await response.text()).toBe(200)
    const spaces = (await response.json()) as Array<{ id: string; isDefault: boolean }>
    const personal = spaces.find((space) => space.isDefault)
    expect(personal, `kein Standard-Space für ${devUser}`).toBeDefined()
    return personal!.id
  } finally {
    await api.dispose()
  }
}

/** Ordnet ein Asset dem Standard-Space von `devUser` zu; eine bestehende Zuordnung bleibt. */
export async function assignToDefaultSpace(
  devUser: string,
  assetType: AssetType,
  assetId: string,
): Promise<void> {
  const spaceId = await defaultSpaceIdOf(devUser)
  const api = await apiAs(devUser)
  try {
    const response = await api.post(`/api/v1/spaces/${spaceId}/assets`, {
      data: { assetType, assetId },
    })
    expect([200, 201], await response.text()).toContain(response.status())
  } finally {
    await api.dispose()
  }
}

/**
 * Ordnet die Wissensbibliothek mit dem Namen `libraryName`, die `devUser` lesen darf, dessen
 * Standard-Space zu.
 */
export async function assignLibraryToDefaultSpace(
  devUser: string,
  libraryName: string,
): Promise<void> {
  const api = await apiAs(devUser)
  let libraryId: string
  try {
    const response = await api.get('/api/v1/libraries')
    expect(response.status(), await response.text()).toBe(200)
    const libraries = (await response.json()) as Array<{ id: string; name: string }>
    const library = libraries.find((candidate) => candidate.name === libraryName)
    expect(library, `${devUser} kann „${libraryName}“ nicht lesen`).toBeDefined()
    libraryId = library!.id
  } finally {
    await api.dispose()
  }
  await assignToDefaultSpace(devUser, 'KNOWLEDGE_LIBRARY', libraryId)
}
