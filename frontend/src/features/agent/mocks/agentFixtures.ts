import { currentMockScenario, generate, scenarioName } from '@/shared/mocks/mockScenario'
import type {
  AgentConfig,
  AgentModel,
  AgentResponse,
  AgentSystemLimits,
  DocumentTemplate,
} from '../types'

export const mockAgentSystemLimits: AgentSystemLimits = {
  max: { calls: 60, minutes: 15, pages: 300 },
  defaults: { calls: 20, minutes: 5, pages: 100 },
}

export const mockAgentModels: AgentModel[] = [
  {
    id: 'model-mistral-large',
    name: 'Mistral Large 2',
    toolCapability: 'CAPABLE',
    isDefault: true,
  },
  { id: 'model-llama-70b', name: 'Llama 3.3 70B', toolCapability: 'CAPABLE', isDefault: false },
  { id: 'model-qwen-72b', name: 'Qwen 2.5 72B', toolCapability: 'CAPABLE', isDefault: false },
  { id: 'model-llama-8b', name: 'Llama 3.1 8B', toolCapability: 'NOT_CAPABLE', isDefault: false },
  { id: 'model-gemma-27b', name: 'Gemma 2 27B', toolCapability: 'UNCHECKED', isDefault: false },
]

const BASE_TEMPLATES: DocumentTemplate[] = [
  {
    id: 'template-bericht',
    name: 'Bericht',
    description: 'Hausvorlage mit Deckblatt, Zusammenfassung, Abschnitten und Fundstellenliste.',
    placeholders: ['titel', 'zusammenfassung', 'abschnitte[]', 'fundstellen[]', 'datum'],
    fileName: 'bericht-hausvorlage.docx',
    myRole: 'VIEWER',
    ownerName: 'Hauptamt',
    updatedAt: '2026-10-28T09:00:00Z',
  },
  {
    id: 'template-vermerk',
    name: 'Vermerk',
    description: 'Aktenvermerk nach Geschäftsordnung mit Aktenzeichen und Verfügung.',
    placeholders: ['aktenzeichen', 'betreff', 'sachverhalt', 'bewertung', 'verfuegung'],
    fileName: 'vermerk.docx',
    myRole: 'VIEWER',
    ownerName: 'Hauptamt',
    updatedAt: '2026-10-12T09:00:00Z',
  },
  {
    id: 'template-stellungnahme',
    name: 'Stellungnahme an den Rat',
    description: 'Stellungnahme der Verwaltung zu einer Ratsvorlage, mit Beschlussvorschlag.',
    placeholders: ['vorlage_nr', 'betreff', 'stellungnahme', 'beschlussvorschlag', 'anlagen[]'],
    fileName: 'stellungnahme-rat.docx',
    myRole: 'OWNER',
    ownerName: 'Büro des Rates',
    updatedAt: '2026-09-30T09:00:00Z',
  },
]

function baseAgents(): AgentResponse[] {
  return [
    {
      id: 'agent-recherche',
      responsibleUnit: 'Hauptamt, Digitalisierung',
      version: 3,
      locked: false,
      builtIn: true,
      name: 'Recherche',
      description:
        'Sucht in mehreren Schritten, benennt Widersprüche und liefert einen Bericht mit Fundstellen.',
      instruction:
        'Zerlege die Frage in Teilfragen und suche zu jeder gezielt. Lies wichtige Fundstellen im Zusammenhang nach. Stelle widersprüchliche Aussagen nebeneinander und nenne beide Quellen. Schließe mit einer kurzen Zusammenfassung und offenen Punkten.',
      libraryIds: ['library-referat-50', 'library-dienstanweisungen', 'library-s3-protokolle'],
      tools: ['search_knowledge', 'read_document', 'list_sources', 'run_code', 'ask_user'],
      limits: { calls: 40, minutes: 10, pages: 200 },
      planStep: 'CONFIRM',
      namesContradictions: true,
      output: 'FILE',
      templateIds: ['template-bericht'],
      fileFormat: 'PDF',
      modelId: null,
      myRole: 'OWNER',
      ownerType: 'GROUP',
      ownerName: 'Systemverwaltung',
      allAccounts: true,
      updatedAt: '2026-11-09T08:00:00Z',
    },
    {
      id: 'agent-vergabe',
      responsibleUnit: 'Zentrale Vergabestelle',
      version: 5,
      locked: false,
      builtIn: false,
      name: 'Vergabeprüfung',
      description: 'Prüft Vergabeunterlagen gegen UVgO, VgV und die Dienstanweisung Beschaffung.',
      instruction:
        'Prüfe die genannten Vergabeunterlagen gegen UVgO, VgV und die Dienstanweisung Beschaffung. Nenne jede Abweichung mit Fundstelle und Schweregrad.',
      libraryIds: ['library-dienstanweisungen', 'library-referat-50'],
      tools: ['search_knowledge', 'read_document'],
      limits: { calls: 20, minutes: 5, pages: 100 },
      planStep: 'SHOW',
      namesContradictions: true,
      output: 'FILE',
      templateIds: ['template-vermerk'],
      fileFormat: 'DOCX',
      modelId: 'model-llama-70b',
      myRole: 'OWNER',
      ownerType: 'GROUP',
      ownerName: 'Zentrale Vergabestelle',
      allAccounts: false,
      updatedAt: '2026-11-04T14:20:00Z',
    },
    {
      id: 'agent-buergeranfragen',
      responsibleUnit: 'Bürgerbüro',
      version: 2,
      locked: false,
      builtIn: false,
      name: 'Bürgeranfragen',
      description: 'Entwirft Antwortschreiben auf Bürgeranfragen in einfacher Sprache.',
      instruction:
        'Entwirf ein Antwortschreiben in einfacher Sprache. Stütze jede Aussage auf eine Fundstelle. Frage nach, wenn Name oder Anliegen fehlen.',
      libraryIds: ['library-dienstanweisungen'],
      tools: ['search_knowledge', 'read_document', 'ask_user'],
      limits: { calls: 12, minutes: 3, pages: 60 },
      planStep: 'OFF',
      namesContradictions: false,
      output: 'CHAT',
      templateIds: [],
      fileFormat: 'DOCX',
      modelId: null,
      myRole: 'EDITOR',
      ownerType: 'USER',
      ownerName: 'Maier, Anna',
      allAccounts: false,
      updatedAt: '2026-10-21T10:05:00Z',
    },
    {
      id: 'agent-sitzung',
      responsibleUnit: 'Büro des Rates',
      version: 4,
      locked: true,
      builtIn: false,
      name: 'Sitzungsvorbereitung',
      description:
        'Fasst Ratsvorlagen zusammen, rechnet Haushaltszahlen nach und entwirft Stellungnahmen.',
      instruction:
        'Fasse die Vorlage zusammen, prüfe die Haushaltszahlen rechnerisch und entwirf eine Stellungnahme mit Beschlussvorschlag.',
      libraryIds: ['library-s3-protokolle'],
      tools: ['search_knowledge', 'read_document', 'run_code'],
      limits: { calls: 25, minutes: 8, pages: 150 },
      planStep: 'SHOW',
      namesContradictions: true,
      output: 'FILE',
      templateIds: ['template-stellungnahme'],
      fileFormat: 'DOCX',
      modelId: null,
      myRole: 'MANAGER',
      ownerType: 'GROUP',
      ownerName: 'Büro des Rates',
      allAccounts: false,
      updatedAt: '2026-10-02T16:45:00Z',
    },
  ]
}

function scaleAgents(): AgentResponse[] {
  const base = baseAgents()
  const generated = generate(56, (i): AgentResponse => {
    const pattern = base[1 + (i % 3)]
    return {
      ...pattern,
      id: `agent-gen-${i}`,
      name: scenarioName('Agent', i),
      builtIn: false,
      updatedAt: '2026-08-01T08:00:00Z',
    }
  })
  return [...base, ...generated]
}

function scaleTemplates(): DocumentTemplate[] {
  return [
    ...BASE_TEMPLATES,
    ...generate(37, (i) => ({
      ...BASE_TEMPLATES[i % 3],
      id: `template-gen-${i}`,
      name: scenarioName('Vorlage', i),
    })),
  ]
}

let created: AgentResponse[] = []
const changed = new Map<string, AgentResponse>()

/** The agents the mock user may read in the active scenario, with this tab's changes. */
export function mockAgents(): AgentResponse[] {
  const scenario = currentMockScenario()
  if (scenario === 'agentsoff') return []
  const list = scenario === 'empty' ? [] : scenario === 'scale' ? scaleAgents() : baseAgents()
  return [...list, ...created].map((agent) => changed.get(agent.id) ?? agent)
}

export function mockAgent(id: string): AgentResponse | undefined {
  return mockAgents().find((agent) => agent.id === id)
}

export function mockTemplates(): DocumentTemplate[] {
  const scenario = currentMockScenario()
  if (scenario === 'empty') return []
  return scenario === 'scale' ? scaleTemplates() : BASE_TEMPLATES
}

export function createMockAgent(config: AgentConfig): AgentResponse {
  const agent: AgentResponse = {
    ...config,
    id: `agent-new-${created.length + 1}`,
    builtIn: false,
    locked: false,
    version: 1,
    myRole: 'OWNER',
    ownerType: 'USER',
    ownerName: 'Sie',
    allAccounts: false,
    updatedAt: new Date().toISOString(),
  }
  created = [...created, agent]
  return agent
}

/** The system administration's lock of one agent. */
export function setMockAgentLocked(id: string, locked: boolean): AgentResponse | undefined {
  const current = mockAgent(id)
  if (!current) return undefined
  const next = { ...current, locked }
  changed.set(id, next)
  return next
}

export function updateMockAgent(id: string, config: AgentConfig): AgentResponse | undefined {
  const current = mockAgent(id)
  if (!current) return undefined
  const next = {
    ...current,
    ...config,
    version: current.version + 1,
    updatedAt: new Date().toISOString(),
  }
  changed.set(id, next)
  return next
}
