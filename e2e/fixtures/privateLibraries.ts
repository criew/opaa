import { expect } from '@playwright/test'
import { apiAs } from './externalAccess'
import { NEXTCLOUD_SERVER_URL } from './nextcloud'

/**
 * Preconditions and cleanup for the connected-account and private-library scenarios. What a
 * scenario asserts runs through the UI; profile, creation right, space and member are set up over
 * the API as a dev user.
 */

/** Die Konto-Kennung von `devUser`, wie `GET /api/v1/auth/me` sie nennt. */
export async function accountIdOf(devUser: string): Promise<string> {
  const api = await apiAs(devUser)
  try {
    const response = await api.get('/api/v1/auth/me')
    expect(response.status(), await response.text()).toBe(200)
    return ((await response.json()) as { id: string }).id
  } finally {
    await api.dispose()
  }
}

/**
 * Legt als Systemverwaltung einen Nextcloud-Zugang mit der Besitzart „Person“ an und gibt ihn
 * allen Konten frei (Anlegerecht „Konnektorbibliotheken anlegen“ für genau diesen Zugang). Die
 * Freigabe geht mit dem Zugang verloren, wenn er gelöscht wird.
 */
export async function createPersonProfile(name: string): Promise<string> {
  const api = await apiAs('dev-admin')
  try {
    const created = await api.post('/api/v1/admin/connection-profiles', {
      data: {
        name,
        sourceType: 'NEXTCLOUD',
        serverUrl: NEXTCLOUD_SERVER_URL,
        authMethod: 'PERSONAL_SECRET',
        ownership: 'PERSON',
      },
    })
    expect(created.status(), await created.text()).toBe(201)
    const profileId = ((await created.json()) as { id: string }).id
    const released = await api.post('/api/v1/admin/capabilities/CREATE_CONNECTOR_LIBRARY/grants', {
      data: { subjectType: 'ALL_ACCOUNTS', scope: `PROFILE:${profileId}` },
    })
    expect(released.status(), await released.text()).toBe(201)
    return profileId
  } finally {
    await api.dispose()
  }
}

/** Legt einen Space von `owner` an und nimmt `member` als einfaches Mitglied auf. */
export async function createSpaceWithMember(
  name: string,
  owner: string,
  member: string,
): Promise<string> {
  const memberId = await accountIdOf(member)
  const api = await apiAs(owner)
  try {
    const created = await api.post('/api/v1/spaces', { data: { name } })
    expect(created.status(), await created.text()).toBe(201)
    const spaceId = ((await created.json()) as { id: string }).id
    const added = await api.post(`/api/v1/spaces/${spaceId}/members`, {
      data: { subjectType: 'USER', subjectId: memberId, role: 'MEMBER' },
    })
    expect(added.status(), await added.text()).toBe(201)
    return spaceId
  } finally {
    await api.dispose()
  }
}

export async function deleteSpaceViaApi(owner: string, spaceId: string): Promise<void> {
  const api = await apiAs(owner)
  try {
    await api.delete(`/api/v1/spaces/${spaceId}`)
  } finally {
    await api.dispose()
  }
}

/**
 * Löscht eine private Bibliothek als ihre Besitzerin („Sofort löschen“). Nur sie darf es: Für alle
 * anderen, die Systemverwaltung eingeschlossen, ist die Bibliothek nicht vorhanden. Die Antwort ist
 * 204, oder 202, solange noch ein Lauf endet.
 */
export async function eraseLibraryAsOwner(owner: string, libraryId: string): Promise<void> {
  const api = await apiAs(owner)
  try {
    const response = await api.delete(`/api/v1/libraries/${libraryId}`)
    expect([202, 204, 404], await response.text()).toContain(response.status())
  } finally {
    await api.dispose()
  }
}

/** Trennt das verbundene Konto von `owner` auf dem Zugang; ein bereits getrenntes ist kein Fehler. */
export async function disconnectAccountViaApi(owner: string, profileId: string): Promise<void> {
  const api = await apiAs(owner)
  try {
    const response = await api.delete(`/api/v1/me/connected-accounts/${profileId}`)
    expect([200, 204, 404], await response.text()).toContain(response.status())
  } finally {
    await api.dispose()
  }
}

/** Antwort eines Versuchs, eine Bibliothek an eine Person freizugeben: Status und Fehlercode. */
export interface ShareAttempt {
  status: number
  code: string | null
  error: string | null
}

/** Versucht im Namen von `actor`, `libraryId` der Person `recipientId` als Leser freizugeben. */
export async function tryShareLibrary(
  actor: string,
  libraryId: string,
  recipientId: string,
): Promise<ShareAttempt> {
  const api = await apiAs(actor)
  try {
    const response = await api.post(`/api/v1/assets/KNOWLEDGE_LIBRARY/${libraryId}/grants`, {
      data: { subjectType: 'USER', subjectId: recipientId, role: 'VIEWER' },
    })
    const body = (await response.json().catch(() => ({}))) as { code?: string; error?: string }
    return { status: response.status(), code: body.code ?? null, error: body.error ?? null }
  } finally {
    await api.dispose()
  }
}

/** Der Status von `GET /api/v1/libraries/{id}` im Namen von `devUser`. */
export async function libraryStatusFor(devUser: string, libraryId: string): Promise<number> {
  const api = await apiAs(devUser)
  try {
    return (await api.get(`/api/v1/libraries/${libraryId}`)).status()
  } finally {
    await api.dispose()
  }
}
