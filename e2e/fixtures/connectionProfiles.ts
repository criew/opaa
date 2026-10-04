import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { apiAs } from './externalAccess'

/**
 * Bausteine der Szenarien zu Zugängen, Freigabe und Profilpflicht (test(e2e) #2175, Epic #2147).
 * Was ein Szenario prüft - Zugang anlegen, freigeben, sperren, die Profilpflicht, Zuordnen und die
 * Hinweise -, läuft über die Oberfläche; was nur Vorbedingung oder Aufräumarbeit ist (eine Gruppe,
 * eine Bestandsbibliothek mit eigener Adresse, das Zurücksetzen der Quellart), über die API im
 * Namen eines Dev-Nutzers, wie in external-access.spec.ts.
 */

/** Der Schlüssel der Quellart RSS-Feed in der API. */
export const RSS_FEED = 'RSS_FEED'

export interface IndexingStatus {
  status: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  documentCount: number
  totalDocuments: number
  documentsSkipped: number
  message: string | null
}

async function withApi<T>(devUser: string, work: (api: APIRequestContext) => Promise<T>) {
  const api = await apiAs(devUser)
  try {
    return await work(api)
  } finally {
    await api.dispose()
  }
}

/**
 * Legt als Systemverwaltung eine interne Gruppe mit einem Mitglied an, gibt sie zur Verwendung
 * frei und liefert ihre Kennung - fürs Aufräumen in {@link deleteGroupViaApi}.
 */
export async function createReleasedGroupWithMember(
  name: string,
  memberQuery: string,
): Promise<string> {
  return withApi('dev-admin', async (api) => {
    const created = await api.post('/api/v1/groups', { data: { name } })
    expect(created.status(), await created.text()).toBe(201)
    const group = (await created.json()) as { id: string }
    const users = await api.get('/api/v1/users', {
      params: { query: memberQuery },
    })
    expect(users.status()).toBe(200)
    const [member] = (await users.json()) as Array<{ id: string }>
    expect(member, `kein Konto zu „${memberQuery}“`).toBeDefined()
    const added = await api.post(`/api/v1/groups/${group.id}/members`, {
      data: { userId: member.id },
    })
    expect(added.status(), await added.text()).toBe(201)
    const release = await api.put(`/api/v1/groups/${group.id}/release`, {
      data: { releasedForUse: true },
    })
    expect(release.status(), await release.text()).toBe(200)
    return group.id
  })
}

export async function deleteGroupViaApi(groupId: string): Promise<void> {
  await withApi('dev-admin', async (api) => {
    await api.delete(`/api/v1/groups/${groupId}`)
  })
}

/** Legt im Namen von `devUser` eine RSS-Bibliothek mit eigener Adresse an. */
export async function createOwnAddressRssLibrary(
  devUser: string,
  name: string,
  feedUrl: string,
): Promise<string> {
  return withApi(devUser, async (api) => {
    const response = await api.post('/api/v1/libraries', {
      data: {
        name,
        sourceType: RSS_FEED,
        sourceUrl: feedUrl,
        sourceInsecureSsl: false,
      },
    })
    expect(response.status(), await response.text()).toBe(201)
    return ((await response.json()) as { id: string }).id
  })
}

/** Startet einen Lauf im Namen von `devUser` und wartet, bis er abgeschlossen oder gescheitert ist. */
export async function runIndexingViaApi(
  devUser: string,
  libraryId: string,
): Promise<IndexingStatus> {
  return withApi(devUser, async (api) => {
    const trigger = await api.post(`/api/v1/libraries/${libraryId}/indexing`)
    expect(trigger.status(), await trigger.text()).toBeLessThan(300)
    let last: IndexingStatus | null = null
    await expect
      .poll(
        async () => {
          const response = await api.get(`/api/v1/libraries/${libraryId}/indexing/status`)
          last = (await response.json()) as IndexingStatus
          return last.status
        },
        { timeout: 30_000 },
      )
      .toMatch(/^(COMPLETED|FAILED)$/)
    return last as unknown as IndexingStatus
  })
}

/** Die Kennungen der Zugänge, deren Name mit `prefix` beginnt. */
export async function profileIdsByPrefix(prefix: string): Promise<string[]> {
  return withApi('dev-admin', async (api) => {
    const response = await api.get('/api/v1/admin/connection-profiles')
    expect(response.status(), await response.text()).toBe(200)
    const profiles = (await response.json()) as Array<{
      id: string
      name: string
    }>
    return profiles.filter((profile) => profile.name.startsWith(prefix)).map((p) => p.id)
  })
}

/**
 * Setzt die Quellart RSS-Feed in den Ausgangszustand der Suite zurück (keine Profilpflicht, nicht
 * gesperrt) und löscht die Zugänge mit `prefix` - entsperrt, damit das Löschen nicht an einer
 * Sperre scheitert. Läuft auch nach einem fehlgeschlagenen Szenario, damit keine spätere
 * RSS-Bibliothek der Suite an einer liegengebliebenen Pflicht scheitert.
 */
export async function resetRssConnectorAndProfiles(prefix: string): Promise<void> {
  const ids = await profileIdsByPrefix(prefix)
  await withApi('dev-admin', async (api) => {
    const requirement = await api.put(
      `/api/v1/admin/connector-types/${RSS_FEED}/profile-requirement`,
      { data: { required: false } },
    )
    expect(requirement.status(), await requirement.text()).toBe(200)
    const lock = await api.put(`/api/v1/admin/connector-types/${RSS_FEED}/lock`, {
      data: { locked: false },
    })
    expect(lock.status(), await lock.text()).toBe(200)
    for (const id of ids) {
      await api.put(`/api/v1/admin/connection-profiles/${id}/lock`, {
        data: { locked: false },
      })
      const deleted = await api.delete(`/api/v1/admin/connection-profiles/${id}`)
      expect(deleted.status(), await deleted.text()).toBeLessThan(300)
    }
  })
}

/** Die Zeile eines Zugangs in der Liste unter „Administration → Zugänge“. */
export function profileRow(page: Page, profileName: string): Locator {
  return page
    .getByRole('table', { name: 'Zugänge' })
    .getByRole('row')
    .filter({ hasText: profileName })
}

/** Öffnet „Administration → Zugänge“ und wartet auf die Liste der Quellarten. */
export async function gotoConnectionProfiles(page: Page): Promise<void> {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/admin/connector-types',
    ),
    page.goto('/admin/connection-profiles'),
  ])
}

/** Die Rückfrage aus confirmStore (`#confirm-question`), wie in library-wizard-and-sharing.spec.ts. */
export function confirmDialog(page: Page): Locator {
  return page.getByRole('dialog').filter({ has: page.locator('#confirm-question') })
}

/** Bestätigt die offene Rückfrage, nachdem sie `question` gestellt hat. */
export async function acceptConfirm(page: Page, question: string | RegExp): Promise<void> {
  const confirm = confirmDialog(page)
  await expect(confirm).toContainText(question)
  await confirm.locator('#confirm-accept').click()
  await expect(confirm).toHaveCount(0)
}

/**
 * Öffnet die Detailseite einer Bibliothek und wartet auf ihre Statusabfrage - ein späteres Warten
 * auf das Laufende kann sich dann nicht an dieser Antwort verfangen.
 */
export async function gotoLibrary(page: Page, libraryId: string): Promise<void> {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        response.url().includes(`/libraries/${libraryId}/indexing/status`),
    ),
    page.goto(`/libraries/${libraryId}`),
  ])
}

/**
 * Klickt „Jetzt indizieren“ und liefert den Endstand des Laufs aus der Statusabfrage der Seite.
 * Dieselbe Lesart wie rss-feed-library.spec.ts: die Antwort, nicht der gerenderte Text.
 */
export async function triggerIndexingInUi(page: Page, libraryId: string): Promise<IndexingStatus> {
  const completion = page.waitForResponse(
    async (response) => {
      if (response.request().method() !== 'GET') return false
      if (!response.url().includes(`/libraries/${libraryId}/indexing/status`)) return false
      const body = (await response.json().catch(() => null)) as IndexingStatus | null
      return body != null && (body.status === 'COMPLETED' || body.status === 'FAILED')
    },
    { timeout: 30_000 },
  )
  await page.getByRole('button', { name: 'Jetzt indizieren' }).click()
  return (await completion).json()
}

/**
 * Setzt im Chat eine `@`-Referenz auf die Bibliothek `libraryName` (ohne Leerzeichen, siehe
 * space-chats.spec.ts) - sie ersetzt @Space-Wissen und grenzt die Suche auf genau diese Bibliothek
 * ein.
 */
export async function referenceLibrary(page: Page, libraryName: string): Promise<void> {
  await page.getByPlaceholder('Nachricht eingeben …').fill(`@${libraryName}`)
  await page.getByRole('option', { name: libraryName }).click()
  await expect(page.getByLabel(`Bibliotheksreferenz ${libraryName} entfernen`)).toBeVisible()
}

/**
 * Öffnet das Belegfenster der letzten Antwort und liefert die Belegzeile von `fileName`; der
 * Aufrufer schließt es mit Escape.
 */
export async function openEvidenceRow(page: Page, fileName: string): Promise<Locator> {
  const showEvidence = page.getByRole('button', { name: 'Belege anzeigen' }).last()
  await expect(showEvidence).toBeVisible({ timeout: 15_000 })
  await showEvidence.click()
  const drawer = page.getByRole('dialog', { name: 'Belege dieser Antwort' })
  await expect(drawer).toBeVisible()
  return drawer.getByTestId('evidence-doc').filter({ hasText: fileName })
}
