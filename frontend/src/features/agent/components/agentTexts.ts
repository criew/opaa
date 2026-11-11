import { formatNumber } from '@/shared/utils/formatDate'
import type { AgentLimits, AgentPlanStep } from '@/shared/api/types'

export interface ToolText {
  name: string
  label: string
  description: string
}

/** The tools of the first cut under the names a clerk reads, in the order of every list. */
export const AGENT_TOOLS: ToolText[] = [
  {
    name: 'search_knowledge',
    label: 'Im Wissen suchen',
    description: 'Sucht in den Bibliotheken, auch mehrmals und mit Filtern.',
  },
  {
    name: 'read_document',
    label: 'Dokument lesen',
    description: 'Liest um eine Fundstelle herum weiter oder das ganze Dokument.',
  },
  {
    name: 'list_sources',
    label: 'Bestand auflisten',
    description: 'Sieht nach, welche Bibliotheken und Dokumente es gibt.',
  },
  {
    name: 'run_code',
    label: 'Rechnen',
    description: 'Wertet Tabellen aus und rechnet in einer abgeschotteten Umgebung.',
  },
  {
    name: 'ask_user',
    label: 'Rückfragen',
    description: 'Fragt bei Unklarem nach; der Lauf endet mit der Frage.',
  },
  {
    name: 'fill_template',
    label: 'Vorlage ausfüllen',
    description: 'Legt das Ergebnis als Word-Datei und PDF nach einer zugeordneten Vorlage ab.',
  },
]

export function toolLabel(name: string): string {
  return AGENT_TOOLS.find((tool) => tool.name === name)?.label ?? name
}

export const PLAN_STEP_OPTIONS: { value: AgentPlanStep; label: string; description: string }[] = [
  {
    value: 'OFF',
    label: 'Gleich loslegen',
    description: 'Der Agent beginnt sofort mit der Arbeit.',
  },
  {
    value: 'CONFIRM',
    label: 'Plan bestätigen lassen',
    description: 'Der Agent zeigt zuerst seinen Plan; erst Ihr „Los“ startet die Arbeit.',
  },
]

export function planStepLabel(step: AgentPlanStep): string {
  return PLAN_STEP_OPTIONS.find((option) => option.value === step)?.label ?? step
}

export type LimitKey = keyof AgentLimits

export const LIMIT_FIELDS: { key: LimitKey; label: string; unit: string }[] = [
  { key: 'maxToolCalls', label: 'Werkzeugaufrufe', unit: 'je Lauf' },
  { key: 'maxSearchCalls', label: 'Suchen', unit: 'je Lauf' },
  { key: 'maxDurationSeconds', label: 'Laufzeit', unit: 'Sekunden' },
  { key: 'maxCollectedChars', label: 'Lesemenge', unit: 'Zeichen' },
]

/** One line per agent: the installation's limits or the agent's own, in the words above. */
export function limitsSummary(limits: AgentLimits): string {
  const own = LIMIT_FIELDS.filter((field) => limits[field.key] != null)
  if (own.length === 0) return 'Grenzen der Installation'
  return own.map((field) => `${field.label}: ${formatNumber(limits[field.key] ?? 0)}`).join(' · ')
}

export function countLabel(count: number, one: string, many: string): string {
  return count === 1 ? `1 ${one}` : `${count} ${many}`
}

export const NAME_PLACEHOLDER = 'z. B. Vergabeprüfung'
export const PURPOSE_HELP = 'Steht in der Auswahl im Chat unter dem Namen.'
export const INSTRUCTION_PLACEHOLDER =
  'z. B. Prüfe Vergabeunterlagen gegen UVgO und Dienstanweisung. Nenne jede Abweichung mit Fundstelle.'
export const INSTRUCTION_HELP = 'Ergänzt die festen Regeln von OPAA, ersetzt sie nicht.'
export const NAME_MAX = 255
export const PURPOSE_MAX = 2000
export const INSTRUCTION_MAX = 8000
