import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { apiAs } from './externalAccess'

/**
 * Bausteine der Szenarien zu Prompt-Bibliothek und Katalog (test(e2e) #1904, Epic #1726). Was ein
 * Szenario prüft - Anlegen, Freigeben, Listen, Finden, Einsetzen - läuft über die Oberfläche; was
 * nur Vorbedingung ist (eine zweite Bibliothek, eine Gruppe, eine Wissensbibliothek für den
 * Katalog), über die API im Namen eines Dev-Nutzers, wie in external-access.spec.ts.
 */

export type AssetVisibility = 'PRIVATE' | 'SHARED' | 'ORGANIZATION'

async function created<T>(response: Awaited<ReturnType<APIRequestContext['post']>>): Promise<T> {
  expect(response.status(), await response.text()).toBe(201)
  return (await response.json()) as T
}

/** Legt eine Prompt-Bibliothek im Namen von `devUser` an und gibt ihre Kennung zurück. */
export async function createPromptLibraryViaApi(
  devUser: string,
  library: { name: string; description?: string; visibility?: AssetVisibility; listed?: boolean },
): Promise<string> {
  const api = await apiAs(devUser)
  try {
    const body = await created<{ id: string }>(
      await api.post('/api/v1/prompt-libraries', { data: library }),
    )
    return body.id
  } finally {
    await api.dispose()
  }
}

/** Legt eine Upload-Wissensbibliothek im Namen von `devUser` an und gibt ihre Kennung zurück. */
export async function createKnowledgeLibraryViaApi(
  devUser: string,
  library: { name: string; description?: string; visibility?: AssetVisibility; listed?: boolean },
): Promise<string> {
  const api = await apiAs(devUser)
  try {
    const body = await created<{ id: string }>(
      await api.post('/api/v1/libraries', { data: { ...library, sourceType: 'UPLOAD' } }),
    )
    return body.id
  } finally {
    await api.dispose()
  }
}

/**
 * Legt als Systemverwaltung eine interne Gruppe mit einem Mitglied an und gibt sie zur Verwendung
 * frei - erst dann bietet der Rechtedialog sie auch Personen ohne Systemrolle an.
 */
export async function createReleasedGroupViaApi(name: string, memberQuery: string): Promise<void> {
  const api = await apiAs('dev-admin')
  try {
    const group = await created<{ id: string }>(await api.post('/api/v1/groups', { data: { name } }))
    const users = await api.get('/api/v1/users', { params: { query: memberQuery } })
    expect(users.status()).toBe(200)
    const [member] = (await users.json()) as Array<{ id: string }>
    expect(member, `kein Konto zu „${memberQuery}“`).toBeDefined()
    await created(
      await api.post(`/api/v1/groups/${group.id}/members`, { data: { userId: member.id } }),
    )
    const release = await api.put(`/api/v1/groups/${group.id}/release`, {
      data: { releasedForUse: true },
    })
    expect(release.status(), await release.text()).toBe(200)
  } finally {
    await api.dispose()
  }
}

/** Öffnet die Übersicht „Prompts“ und wartet auf ihre Liste. */
export async function gotoPromptLibraries(page: Page): Promise<void> {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        response.url().endsWith('/api/v1/prompt-libraries'),
    ),
    page.goto('/prompts'),
  ])
}

/** Öffnet die Detailseite einer Prompt-Bibliothek über ihre Kachel in der Übersicht. */
export async function gotoPromptLibraryDetail(page: Page, name: string): Promise<void> {
  await gotoPromptLibraries(page)
  await page.getByRole('link', { name: new RegExp(escapeRegExp(name)) }).click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
}

/**
 * Öffnet den Katalog und sucht nach `query` - die Suche läuft auf dem Server, deshalb wird auf die
 * Antwort gewartet, die den Suchtext trägt.
 */
export async function searchCatalog(page: Page, query: string): Promise<void> {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' && response.url().includes('/api/v1/catalog'),
    ),
    page.goto('/catalog'),
  ])
  await Promise.all([
    page.waitForResponse((response) => {
      if (response.request().method() !== 'GET') return false
      const url = new URL(response.url())
      return url.pathname.endsWith('/api/v1/catalog') && url.searchParams.get('q') === query
    }),
    page.getByRole('textbox', { name: 'Suchen' }).fill(query),
  ])
}

/** Ein zugänglicher Katalogeintrag - eine Kachel, die als Link zur Detailseite führt. */
export function accessibleCatalogEntry(page: Page, name: string): Locator {
  return page.getByRole('link', { name: new RegExp(escapeRegExp(name)) })
}

/** Ein gelisteter Eintrag ohne Zugriff - eine Kachel ohne Link. */
export function listedCatalogEntry(page: Page, name: string): Locator {
  return page.getByRole('article', { name })
}

export function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
