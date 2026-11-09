import { http, HttpResponse } from 'msw'
import { currentMockScenario } from '@/shared/mocks/mockScenario'
import type { AgentConfig } from '../types'
import {
  createMockAgent,
  mockAgent,
  mockAgentModels,
  mockAgents,
  mockAgentSystemLimits,
  mockTemplates,
  setMockAgentLocked,
  updateMockAgent,
} from './agentFixtures'

const NOT_FOUND = { error: 'Agent nicht gefunden', status: 404 }

/** Mirrors the planned rule: an agent's limits may only lower the system's. */
function limitError(config: AgentConfig): string | null {
  const max = mockAgentSystemLimits.max
  if (config.limits.calls > max.calls) return `Höchstens ${max.calls} Schritte sind erlaubt.`
  if (config.limits.minutes > max.minutes) return `Höchstens ${max.minutes} Minuten sind erlaubt.`
  if (config.limits.pages > max.pages) return `Höchstens ${max.pages} Seiten sind erlaubt.`
  return null
}

export const agentHandlers = [
  http.get('/api/v1/agents', () => HttpResponse.json(mockAgents())),
  http.get('/api/v1/agents/:agentId', ({ params }) => {
    const agent = mockAgent(String(params.agentId))
    return agent ? HttpResponse.json(agent) : HttpResponse.json(NOT_FOUND, { status: 404 })
  }),
  http.post('/api/v1/agents', async ({ request }) => {
    if (currentMockScenario() === 'norights') {
      return HttpResponse.json(
        {
          error:
            'Ihnen fehlt das Anlegerecht „Agenten anlegen“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.',
          status: 403,
        },
        { status: 403 },
      )
    }
    const config = (await request.json()) as AgentConfig
    const error = limitError(config)
    if (error) return HttpResponse.json({ error, status: 400 }, { status: 400 })
    return HttpResponse.json(createMockAgent(config), { status: 201 })
  }),
  http.put('/api/v1/agents/:agentId', async ({ params, request }) => {
    const config = (await request.json()) as AgentConfig
    const error = limitError(config)
    if (error) return HttpResponse.json({ error, status: 400 }, { status: 400 })
    const agent = updateMockAgent(String(params.agentId), config)
    return agent ? HttpResponse.json(agent) : HttpResponse.json(NOT_FOUND, { status: 404 })
  }),
  http.put('/api/v1/agents/:agentId/lock', async ({ params, request }) => {
    const { locked } = (await request.json()) as { locked: boolean }
    const agent = setMockAgentLocked(String(params.agentId), locked)
    return agent ? HttpResponse.json(agent) : HttpResponse.json(NOT_FOUND, { status: 404 })
  }),
  http.get('/api/v1/agent-models', () => HttpResponse.json(mockAgentModels)),
  http.get('/api/v1/agent-limits', () => HttpResponse.json(mockAgentSystemLimits)),
  http.get('/api/v1/document-templates', () => HttpResponse.json(mockTemplates())),
]
