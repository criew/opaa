import type { AgentConfig, AgentLimits, AgentPlanStep, AgentToolId } from './types'

export interface ToolText {
  id: AgentToolId
  label: string
  description: string
}

/** The five tools of the first cut, under the names a clerk reads (#1747, Neuausrichtung). */
export const AGENT_TOOLS: ToolText[] = [
  {
    id: 'search_knowledge',
    label: 'Im Wissen suchen',
    description: 'Sucht in den Bibliotheken, auch mehrmals und mit Filtern.',
  },
  {
    id: 'read_document',
    label: 'Dokument lesen',
    description: 'Liest um eine Fundstelle herum weiter oder das ganze Dokument.',
  },
  {
    id: 'list_sources',
    label: 'Bestand auflisten',
    description: 'Sieht nach, welche Bibliotheken und Dokumente es gibt.',
  },
  {
    id: 'run_code',
    label: 'Rechnen',
    description: 'Wertet Tabellen aus und rechnet in einer abgeschotteten Umgebung.',
  },
  {
    id: 'ask_user',
    label: 'Rückfragen',
    description: 'Fragt bei Unklarem nach und wartet auf Ihre Antwort.',
  },
]

export function toolLabel(id: AgentToolId): string {
  return AGENT_TOOLS.find((tool) => tool.id === id)?.label ?? id
}

export const PLAN_STEP_OPTIONS: { value: AgentPlanStep; label: string; description: string }[] = [
  { value: 'OFF', label: 'Aus', description: 'Der Agent legt gleich los.' },
  { value: 'SHOW', label: 'Anzeigen', description: 'Der Plan steht über der Antwort.' },
  { value: 'CONFIRM', label: 'Bestätigen', description: 'Der Agent wartet auf Ihr „Los“.' },
]

export function planStepLabel(step: AgentPlanStep): string {
  return { OFF: 'Ohne Plan', SHOW: 'Plan anzeigen', CONFIRM: 'Plan bestätigen' }[step]
}

export const LIMIT_FIELDS: { key: keyof AgentLimits; label: string; unit: string }[] = [
  { key: 'calls', label: 'Schritte', unit: 'Werkzeugaufrufe' },
  { key: 'minutes', label: 'Laufzeit', unit: 'Minuten' },
  { key: 'pages', label: 'Lesemenge', unit: 'Seiten' },
]

export function limitsSummary(limits: AgentLimits): string {
  return `${limits.calls} Schritte · ${limits.minutes} Min. · ${limits.pages} Seiten`
}

/** A one-line description of how the agent works, for summaries and the chat. */
export function behaviourSummary(config: AgentConfig): string {
  const parts = [planStepLabel(config.planStep)]
  if (config.namesContradictions) parts.push('benennt Widersprüche')
  parts.push(config.output === 'FILE' ? 'Ergebnis als Datei' : 'Antwort im Chat')
  return parts.join(' · ')
}

export function countLabel(count: number, one: string, many: string): string {
  return count === 1 ? `1 ${one}` : `${count} ${many}`
}

export const NAME_PLACEHOLDER = 'z. B. Vergabeprüfung'
export const INSTRUCTION_PLACEHOLDER =
  'z. B. Prüfe Vergabeunterlagen gegen UVgO und Dienstanweisung. Nenne jede Abweichung mit Fundstelle.'
export const INSTRUCTION_HELP = 'Ergänzt die festen Regeln von OPAA, ersetzt sie nicht.'

/** The stamp every agent result carries: draft mark, agent, version, libraries, template. */
export function resultStamp(
  name: string,
  version: number,
  libraryCount: number,
  templateName: string | null,
): string {
  const parts = [
    'Ungeprüfter Entwurf',
    `${name || 'Agent'}, Fassung ${version}`,
    libraryCount === 0
      ? 'alles Lesbare'
      : libraryCount === 1
        ? '1 Bibliothek'
        : `${libraryCount} Bibliotheken`,
  ]
  if (templateName) parts.push(`Vorlage „${templateName}“`)
  return parts.join(' · ')
}
