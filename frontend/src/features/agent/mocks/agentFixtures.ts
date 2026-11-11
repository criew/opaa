import type { AgentResponse, AgentSettingsResponse } from '@/shared/api/types'

const NOW = '2026-11-09T10:00:00Z'

const INITIAL_AGENTS: Record<string, AgentResponse> = {
  'agent-recherche': {
    id: 'agent-recherche',
    name: 'Recherche',
    description:
      'Trägt Wissen in mehreren Schritten zusammen und antwortet gegliedert mit Fundstellen.',
    instruction:
      'Zerlege die Frage in Teilfragen, suche zu jeder gezielt und lies die Fundstellen nach. Antworte gegliedert und belege jede Aussage mit ihrer Fundstelle.',
    libraries: [],
    hiddenLibraryCount: 0,
    templates: [],
    hiddenTemplateCount: 0,
    toolNames: ['search_knowledge', 'read_document', 'list_sources', 'run_code', 'ask_user'],
    planStep: 'OFF',
    limits: {},
    revision: 1,
    delivered: true,
    ownerType: 'USER',
    ownerId: 'user-admin',
    ownerName: 'Systemverwaltung',
    reach: { allAccounts: true, groupCount: 0, userCount: 0 },
    myRole: 'OWNER',
    responsibility: { function: 'Systemverwaltung', missing: false },
    succession: null,
    createdAt: NOW,
    updatedAt: NOW,
  },
  'agent-vergabe': {
    id: 'agent-vergabe',
    name: 'Vergabeprüfung',
    description: 'Prüft Vergabeunterlagen gegen UVgO und Dienstanweisung.',
    instruction: 'Nenne jede Abweichung mit Fundstelle und Paragraf.',
    libraries: [{ id: 'lib-1', name: 'Vergaberecht' }],
    hiddenLibraryCount: 1,
    templates: [{ id: 'document-template-vermerk', name: 'Vermerk Referat 50' }],
    hiddenTemplateCount: 1,
    toolNames: ['search_knowledge', 'read_document', 'fill_template'],
    planStep: 'CONFIRM',
    limits: { maxToolCalls: 8 },
    revision: 3,
    delivered: false,
    ownerType: 'GROUP',
    ownerId: 'group-referat-50',
    ownerName: 'Referat 50',
    reach: { allAccounts: false, groupCount: 1, userCount: 0 },
    myRole: 'MANAGER',
    responsibility: {
      groupId: 'group-referat-50',
      groupName: 'Referat 50',
      missing: false,
      lock: {
        cause: 'MANUAL',
        reason: 'Anweisung wird nach der Novelle überarbeitet',
        lockedAt: '2026-11-10T08:00:00Z',
      },
    },
    succession: null,
    createdAt: NOW,
    updatedAt: NOW,
  },
}

const INITIAL_SETTINGS: AgentSettingsResponse = {
  enabled: true,
  enabledTools: ['search_knowledge', 'read_document', 'list_sources', 'ask_user'],
  availableTools: [
    'search_knowledge',
    'read_document',
    'list_sources',
    'run_code',
    'ask_user',
    'fill_template',
  ],
  updatedAt: NOW,
  updatedBy: 'Systemverwaltung',
}

// Mutable copies: the handlers read and write them, and the test setup resets them between tests.
export let mockAgents: Record<string, AgentResponse> = structuredClone(INITIAL_AGENTS)
export let mockAgentSettings: AgentSettingsResponse = structuredClone(INITIAL_SETTINGS)

export function resetMockAgents() {
  mockAgents = structuredClone(INITIAL_AGENTS)
  mockAgentSettings = structuredClone(INITIAL_SETTINGS)
}
