import { http, HttpResponse } from 'msw'
import type {
  AgentCreateRequest,
  AgentResponse,
  AgentSettingsUpdateRequest,
  AgentUpdateRequest,
  AssetLockRequest,
  AssetResponsibilityResponse,
  ResponsibleUnitRequest,
} from '@/shared/api/types'
import { mockGroups } from '../../group/mocks/groupFixtures'
import { mockMyCapabilities } from '../../capability/mocks/capabilityFixtures'
import { mockAgentSettings, mockAgents } from './agentFixtures'

function notFound() {
  return HttpResponse.json({ error: 'Agent nicht gefunden' }, { status: 404 })
}

function forbidden() {
  return HttpResponse.json({ error: 'Kein Zugriff auf diesen Agenten' }, { status: 403 })
}

function invalid(field: string, code: string, message: string) {
  return HttpResponse.json(
    { error: 'Die Angaben sind unvollständig.', fieldErrors: [{ field, code, message }] },
    { status: 400 },
  )
}

const offline = () => !mockAgentSettings.enabled

/** The responsibility a submitted unit stands for; `null` when none is named. */
export function responsibilityOf(
  unit: ResponsibleUnitRequest | null | undefined,
): AssetResponsibilityResponse | null {
  if (unit?.groupId) {
    const group = mockGroups.find((g) => g.id === unit.groupId)
    return { groupId: unit.groupId, groupName: group?.name ?? 'Gruppe', missing: false }
  }
  if (unit?.function?.trim()) return { function: unit.function.trim(), missing: false }
  return null
}

const RESPONSIBLE_REQUIRED =
  'Geben Sie die verantwortliche Stelle an: eine Gruppe, Organisationseinheit oder Funktion.'

export const agentHandlers = [
  http.get('/api/v1/agents/availability', () =>
    HttpResponse.json({ enabled: mockAgentSettings.enabled }),
  ),

  // Every readable, unlocked agent stands in every space of the mock; switched off, nothing is offered.
  http.get('/api/v1/agents/available', () =>
    HttpResponse.json(
      offline()
        ? []
        : Object.values(mockAgents)
            .filter((agent) => !agent.responsibility.lock)
            .sort((a, b) => a.name.localeCompare(b.name, 'de'))
            .map((agent) => ({ id: agent.id, name: agent.name, description: agent.description })),
    ),
  ),

  http.get('/api/v1/agents', () =>
    offline()
      ? notFound()
      : HttpResponse.json(
          Object.values(mockAgents).sort((a, b) => a.name.localeCompare(b.name, 'de')),
        ),
  ),

  http.put('/api/v1/agents/:id/lock', async ({ params, request }) => {
    const agent = mockAgents[params.id as string]
    if (offline() || !agent) return notFound()
    const body = (await request.json()) as AssetLockRequest
    if (!body.reason?.trim()) {
      return invalid('reason', 'REQUIRED', 'Geben Sie an, warum gesperrt wird.')
    }
    agent.responsibility = {
      ...agent.responsibility,
      lock: { cause: 'MANUAL', reason: body.reason.trim(), lockedAt: new Date().toISOString() },
    }
    return HttpResponse.json(agent.responsibility)
  }),

  http.delete('/api/v1/agents/:id/lock', ({ params }) => {
    const agent = mockAgents[params.id as string]
    if (offline() || !agent) return notFound()
    agent.responsibility = { ...agent.responsibility, lock: null }
    return HttpResponse.json(agent.responsibility)
  }),

  http.post('/api/v1/agents', async ({ request }) => {
    if (offline()) return notFound()
    if (!mockMyCapabilities.capabilities.includes('CREATE_AGENT')) {
      return HttpResponse.json(
        {
          error:
            'Ihnen fehlt das Anlegerecht „Agenten anlegen“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.',
        },
        { status: 403 },
      )
    }
    const body = (await request.json()) as AgentCreateRequest
    if (!body.name?.trim()) return invalid('name', 'REQUIRED', 'Geben Sie dem Agenten einen Namen.')
    const responsibility = responsibilityOf(body.responsible)
    if (!responsibility) return invalid('responsible', 'REQUIRED', RESPONSIBLE_REQUIRED)
    const group =
      body.ownerType === 'GROUP' ? mockGroups.find((g) => g.id === body.ownerId) : undefined
    const now = new Date().toISOString()
    const agent: AgentResponse = {
      id: `agent-${Math.random().toString(36).slice(2, 10)}`,
      name: body.name.trim(),
      description: body.description ?? null,
      instruction: '',
      libraries: [],
      hiddenLibraryCount: 0,
      toolNames: ['search_knowledge', 'read_document'],
      planStep: 'OFF',
      limits: {},
      revision: 1,
      delivered: false,
      ownerType: group ? 'GROUP' : 'USER',
      ownerId: group?.id ?? 'user-dev',
      ownerName: group?.name ?? 'Entwicklerin',
      reach: { allAccounts: false, groupCount: 0, userCount: 0 },
      myRole: 'OWNER',
      responsibility,
      succession: null,
      createdAt: now,
      updatedAt: now,
    }
    mockAgents[agent.id] = agent
    return HttpResponse.json(agent, { status: 201 })
  }),

  http.get('/api/v1/agents/:id', ({ params }) => {
    const agent = mockAgents[params.id as string]
    return offline() || !agent ? notFound() : HttpResponse.json(agent)
  }),

  http.put('/api/v1/agents/:id', async ({ params, request }) => {
    const agent = mockAgents[params.id as string]
    if (offline() || !agent) return notFound()
    if (agent.myRole !== 'MANAGER' && agent.myRole !== 'OWNER') return forbidden()
    const body = (await request.json()) as AgentUpdateRequest
    if (!body.name?.trim()) return invalid('name', 'REQUIRED', 'Geben Sie dem Agenten einen Namen.')
    const updated: AgentResponse = {
      ...agent,
      name: body.name.trim(),
      description: body.description ?? null,
      instruction: body.instruction,
      libraries: body.libraryIds.map(
        (id) =>
          agent.libraries.find((library) => library.id === id) ?? { id, name: `Bibliothek ${id}` },
      ),
      toolNames: body.toolNames,
      planStep: body.planStep,
      limits: body.limits,
      // Like the backend: only a change of what a run uses is a new revision, never a new name.
      revision:
        JSON.stringify([
          agent.instruction,
          agent.libraries.map((library) => library.id),
          agent.toolNames,
          agent.planStep,
          agent.limits,
        ]) ===
        JSON.stringify([
          body.instruction,
          body.libraryIds,
          body.toolNames,
          body.planStep,
          body.limits,
        ])
          ? agent.revision
          : agent.revision + 1,
      responsibility: body.responsible
        ? { ...agent.responsibility, ...responsibilityOf(body.responsible) }
        : agent.responsibility,
      updatedAt: new Date().toISOString(),
    }
    mockAgents[agent.id] = updated
    return HttpResponse.json(updated)
  }),

  http.delete('/api/v1/agents/:id', ({ params }) => {
    const agent = mockAgents[params.id as string]
    if (offline() || !agent) return notFound()
    if (agent.myRole !== 'OWNER') return forbidden()
    delete mockAgents[agent.id]
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/system/agents', () => HttpResponse.json(mockAgentSettings)),

  http.put('/api/v1/system/agents', async ({ request }) => {
    const body = (await request.json()) as AgentSettingsUpdateRequest
    mockAgentSettings.enabled = body.enabled
    mockAgentSettings.enabledTools = body.enabledTools
    mockAgentSettings.updatedAt = new Date().toISOString()
    return HttpResponse.json(mockAgentSettings)
  }),
]
