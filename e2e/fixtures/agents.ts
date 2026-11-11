import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { apiAs } from './externalAccess'

/**
 * Bausteine der Szenarien zum Agenten-Paket (test(e2e) #3680, Epic #1747). Was ein Szenario prüft,
 * läuft über die Oberfläche; Schalter, Modellfähigkeit, Bibliotheken und Zuordnungen sind
 * Vorbedingung und laufen über die API im Namen eines Dev-Nutzers. Schalter und Modell sind
 * Zustand der ganzen Installation und werden vom Szenario zurückgesetzt, das sie ändert.
 */

export const AI_STUB_BASE_URL = process.env.E2E_AI_STUB_BASE_URL ?? 'http://localhost:18089'

type AssetType = 'KNOWLEDGE_LIBRARY' | 'PROMPT_LIBRARY' | 'AGENT'

export interface AgentSettings {
  enabled: boolean
  enabledTools: string[]
  availableTools: string[]
}

/** Werkzeuge ohne Sandbox: alle, die ein Agent in der Suite nutzen kann. */
export const TOOLS_WITHOUT_SANDBOX = ['search_knowledge', 'read_document', 'list_sources', 'ask_user']

async function ok(response: Awaited<ReturnType<APIRequestContext['get']>>): Promise<void> {
  expect(response.ok(), `${response.url()}: ${response.status()} ${await response.text()}`).toBe(
    true,
  )
}

/** Stellt den Schalter „Agenten“ und die freigegebenen Werkzeuge ein; gibt den Zustand davor zurück. */
export async function setAgentSettings(
  enabled: boolean,
  enabledTools: string[] = TOOLS_WITHOUT_SANDBOX,
): Promise<AgentSettings> {
  const api = await apiAs('dev-admin')
  try {
    const before = (await (await api.get('/api/v1/system/agents')).json()) as AgentSettings
    await ok(await api.put('/api/v1/system/agents', { data: { enabled, enabledTools } }))
    return before
  } finally {
    await api.dispose()
  }
}

export async function restoreAgentSettings(before: AgentSettings | null): Promise<void> {
  if (before === null) return
  const api = await apiAs('dev-admin')
  try {
    await api.put('/api/v1/system/agents', {
      data: { enabled: before.enabled, enabledTools: before.enabledTools },
    })
  } finally {
    await api.dispose()
  }
}

/** Stellt den Schalter „Vorlagen“ ein; gibt den Zustand davor zurück. */
export async function setTemplatesEnabled(enabled: boolean): Promise<boolean> {
  const api = await apiAs('dev-admin')
  try {
    const before = (await (await api.get('/api/v1/system/document-templates')).json()) as {
      enabled: boolean
    }
    await ok(await api.put('/api/v1/system/document-templates', { data: { enabled } }))
    return before.enabled
  } finally {
    await api.dispose()
  }
}

type ToolCapability = 'CAPABLE' | 'NOT_CAPABLE' | 'UNVERIFIED'

/**
 * Hält die Werkzeugfähigkeit des aktiven Chat-Modells fest (ohne sie weist ein Agentenlauf ab,
 * ADR-0070). Gibt den Wert davor zurück.
 */
export async function setToolCapability(value: ToolCapability): Promise<ToolCapability> {
  const api = await apiAs('dev-admin')
  try {
    const models = (await (await api.get('/api/v1/admin/models')).json()) as Array<{
      id: string
      active: boolean
      baseUrl: string
      modelIdentifier: string
      toolCapability: { value: ToolCapability }
    }>
    const active = models.find((model) => model.active)
    expect(active, 'kein aktives Chat-Modell').toBeDefined()
    await ok(
      await api.put(`/api/v1/admin/models/${active!.id}/tool-capability`, {
        data: {
          value,
          ...(value === 'UNVERIFIED' ? {} : { basis: 'MANUAL' }),
          baseUrl: active!.baseUrl,
          modelIdentifier: active!.modelIdentifier,
        },
      }),
    )
    return active!.toolCapability.value
  } finally {
    await api.dispose()
  }
}

/** Die verantwortliche Stelle, die jeder angelegte Agent braucht (#3677). */
export const RESPONSIBLE_UNIT = 'Referat E2E - Organisation'

export interface AgentConfig {
  name: string
  description?: string
  instruction?: string
  libraryIds?: string[]
  toolNames?: string[]
  planStep?: 'OFF' | 'CONFIRM'
  limits?: Record<string, number>
  templateIds?: string[]
}

/** Legt einen Agenten im Namen von `owner` an und richtet ihn ein; gibt seine Kennung zurück. */
export async function createAgentViaApi(owner: string, config: AgentConfig): Promise<string> {
  const api = await apiAs(owner)
  try {
    const created = await api.post('/api/v1/agents', {
      data: {
        name: config.name,
        description: config.description ?? null,
        responsible: { function: RESPONSIBLE_UNIT },
      },
    })
    expect(created.status(), await created.text()).toBe(201)
    const { id } = (await created.json()) as { id: string }
    await ok(
      await api.put(`/api/v1/agents/${id}`, {
        data: {
          name: config.name,
          description: config.description ?? null,
          instruction: config.instruction ?? '',
          libraryIds: config.libraryIds ?? [],
          toolNames: config.toolNames ?? ['search_knowledge', 'read_document'],
          planStep: config.planStep ?? 'OFF',
          limits: config.limits ?? {},
          ...(config.templateIds ? { templateIds: config.templateIds } : {}),
        },
      }),
    )
    return id
  } finally {
    await api.dispose()
  }
}

export async function deleteAgentViaApi(owner: string, agentId: string): Promise<void> {
  const api = await apiAs(owner)
  try {
    await api.delete(`/api/v1/agents/${agentId}`)
  } finally {
    await api.dispose()
  }
}

/**
 * Legt eine Upload-Bibliothek im Namen von `owner` an, lädt die Dokumente hoch und wartet, bis alle
 * indiziert sind. `documents` sind Name und Text.
 */
export async function createIndexedLibrary(
  owner: string,
  name: string,
  documents: Array<{ name: string; text: string }>,
): Promise<string> {
  const api = await apiAs(owner)
  try {
    const created = await api.post('/api/v1/libraries', {
      data: { name, sourceType: 'UPLOAD' },
    })
    expect(created.status(), await created.text()).toBe(201)
    const { id } = (await created.json()) as { id: string }
    for (const document of documents) {
      const uploaded = await api.post(`/api/v1/libraries/${id}/documents`, {
        multipart: {
          file: {
            name: document.name,
            mimeType: 'text/plain',
            buffer: Buffer.from(document.text, 'utf-8'),
          },
        },
      })
      expect(uploaded.ok(), await uploaded.text()).toBe(true)
    }
    await expect
      .poll(
        async () => {
          const response = await api.get(`/api/v1/libraries/${id}/documents?size=100`)
          const body = (await response.json()) as { items: Array<{ status: string }> }
          return body.items.map((item) => item.status).join(',')
        },
        { timeout: 60_000 },
      )
      .toBe(documents.map(() => 'INDEXED').join(','))
    return id
  } finally {
    await api.dispose()
  }
}

/** Ordnet ein Asset einem Space zu; `actor` darf dort zuordnen (Space-Verwaltung). */
export async function assignToSpace(
  actor: string,
  spaceId: string,
  assetType: AssetType,
  assetId: string,
): Promise<void> {
  const api = await apiAs(actor)
  try {
    const response = await api.post(`/api/v1/spaces/${spaceId}/assets`, {
      data: { assetType, assetId },
    })
    expect([200, 201], await response.text()).toContain(response.status())
  } finally {
    await api.dispose()
  }
}

/** Nimmt `member` als einfaches Mitglied in den Space von `owner` auf. */
export async function addSpaceMember(owner: string, spaceId: string, member: string): Promise<void> {
  const memberId = await accountId(member)
  const api = await apiAs(owner)
  try {
    const added = await api.post(`/api/v1/spaces/${spaceId}/members`, {
      data: { subjectType: 'USER', subjectId: memberId, role: 'MEMBER' },
    })
    expect(added.status(), await added.text()).toBe(201)
  } finally {
    await api.dispose()
  }
}

export async function accountId(devUser: string): Promise<string> {
  const api = await apiAs(devUser)
  try {
    const response = await api.get('/api/v1/auth/me')
    expect(response.status(), await response.text()).toBe(200)
    return ((await response.json()) as { id: string }).id
  } finally {
    await api.dispose()
  }
}

/** Gibt einen Nutzer oder eine Gruppe als Leser (VIEWER) auf ein Asset frei. */
export async function grantViewer(
  actor: string,
  assetType: AssetType,
  assetId: string,
  subject: { subjectType: 'USER' | 'GROUP'; subjectId: string },
): Promise<void> {
  const api = await apiAs(actor)
  try {
    const response = await api.post(`/api/v1/assets/${assetType}/${assetId}/grants`, {
      data: { ...subject, role: 'VIEWER' },
    })
    expect([200, 201], await response.text()).toContain(response.status())
  } finally {
    await api.dispose()
  }
}

/** Die Antworten des Chats auf dem Bildschirm, erkennbar an der KI-Kennzeichnung. */
export function answers(page: Page): Locator {
  return page
    .getByTestId('message-list')
    .locator('[data-message-id]')
    .filter({ has: page.getByTestId('ai-answer-mark') })
}

/** Tippt `@` ins Eingabefeld und wählt den Agenten `name` aus der Gruppe „Agenten“. */
export async function chooseAgent(page: Page, name: string): Promise<void> {
  const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
  await input.pressSequentially('@')
  await page
    .getByRole('group', { name: 'Agenten' })
    .getByRole('option', { name: new RegExp(`^${escapeForRegExp(name)}`) })
    .click()
  await expect(page.getByTestId('chosen-agent-chip')).toHaveText(`Agent: ${name}`)
}

/** Wählt den Agenten `name` per `@` und sendet `question` an ihn. */
export async function askAgent(page: Page, name: string, question: string): Promise<void> {
  await chooseAgent(page, name)
  await page.getByRole('combobox', { name: 'Nachricht eingeben' }).fill(question)
  await page.getByRole('button', { name: 'Senden' }).click()
}

function escapeForRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** Wie viele Agentenaufrufe der KI-Stub gerade festhält (Auslöser `E2E-AGENT-PAUSE`). */
export async function heldAgentCalls(): Promise<number> {
  const response = await fetch(`${AI_STUB_BASE_URL}/control/agent-hold`)
  return ((await response.json()) as { waiting: number }).waiting
}

/** Lässt alle festgehaltenen Agentenaufrufe des KI-Stubs weiterlaufen. */
export async function releaseAgentCalls(): Promise<void> {
  await fetch(`${AI_STUB_BASE_URL}/control/agent-release`, { method: 'POST' })
}

/** Legt einen Prompt in einer Prompt-Bibliothek von `owner` an. */
export async function createPromptViaApi(
  owner: string,
  libraryId: string,
  prompt: { name: string; title: string; text: string },
): Promise<void> {
  const api = await apiAs(owner)
  try {
    const response = await api.post(`/api/v1/prompt-libraries/${libraryId}/prompts`, {
      data: prompt,
    })
    expect(response.status(), await response.text()).toBe(201)
  } finally {
    await api.dispose()
  }
}

/** Räumt eine Gruppe auf; ein Fehler (etwa weil sie schon fort ist) zählt nicht. */
export async function deleteGroupViaApi(groupId: string): Promise<void> {
  const api = await apiAs('dev-admin')
  try {
    await api.delete(`/api/v1/groups/${groupId}`)
  } finally {
    await api.dispose()
  }
}
