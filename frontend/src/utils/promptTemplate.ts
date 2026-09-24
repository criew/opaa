import type { PromptVariable, PromptVariableType } from '../types/api'

/**
 * The variable syntax of a prompt text, mirroring the backend's `PromptTemplate`
 * (docs/features/spaces-and-assets.md#prompt-bibliothek): a placeholder is `{{name}}` - a letter
 * first, then letters, digits and underscores; blanks inside the braces are tolerated and a system
 * variable is recognised in any case, both normalised by the server on saving. Any other `{{…}}`
 * is invalid rather than text. The client checks are a courtesy before saving; the server decides.
 */
export const SYSTEM_VARIABLES = ['CURRENT_DATE', 'USER_NAME'] as const

export const PROMPT_NAME_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/
export const PROMPT_NAME_MAX_LENGTH = 64
export const PROMPT_TITLE_MAX_LENGTH = 255
export const PROMPT_DESCRIPTION_MAX_LENGTH = 2000
export const PROMPT_TEXT_MAX_LENGTH = 8000
export const PROMPT_MAX_VARIABLES = 20
export const PROMPT_MAX_OPTIONS = 50
const VARIABLE_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_]*$/
const VARIABLE_NAME_MAX_LENGTH = 64
const LABEL_MAX_LENGTH = 255
const OPTION_MAX_LENGTH = 255
const DEFAULT_MAX_LENGTH = 2000
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

export type PromptTextSegment =
  | { kind: 'text'; value: string }
  | { kind: 'variable'; value: string; name: string }
  | { kind: 'system'; value: string; name: string }
  | { kind: 'invalid'; value: string }

export function isSystemVariable(name: string): boolean {
  return (SYSTEM_VARIABLES as readonly string[]).includes(name)
}

function isValidVariableName(name: string): boolean {
  return VARIABLE_NAME_PATTERN.test(name) && name.length <= VARIABLE_NAME_MAX_LENGTH
}

/** Splits a prompt text into plain text and placeholders, in order - the basis of highlighting. */
export function splitPromptText(text: string): PromptTextSegment[] {
  const segments: PromptTextSegment[] = []
  const pattern = /\{\{([\s\S]*?)\}\}/g
  let last = 0
  for (let match = pattern.exec(text); match !== null; match = pattern.exec(text)) {
    if (match.index > last) segments.push({ kind: 'text', value: text.slice(last, match.index) })
    const name = match[1].trim()
    if (!isValidVariableName(name)) {
      segments.push({ kind: 'invalid', value: match[0] })
    } else if (isSystemVariable(name.toUpperCase())) {
      segments.push({ kind: 'system', value: match[0], name: name.toUpperCase() })
    } else {
      segments.push({ kind: 'variable', value: match[0], name })
    }
    last = match.index + match[0].length
  }
  if (last < text.length) segments.push({ kind: 'text', value: text.slice(last) })
  return segments
}

/** The names of the text's own variables in order of first use, without system variables. */
export function variableNames(text: string): string[] {
  const names: string[] = []
  for (const segment of splitPromptText(text)) {
    if (segment.kind === 'variable' && !names.includes(segment.name)) names.push(segment.name)
  }
  return names
}

/** A fresh definition for a newly used placeholder: a single line, labelled with its name. */
export function defaultVariable(name: string): PromptVariable {
  return { name, label: name, type: 'TEXT', required: false, defaultValue: null, options: null }
}

/**
 * The definitions the text needs, in order of use: an existing definition where one is known,
 * otherwise `defaultVariable`. Definitions the text no longer uses drop out - the server refuses
 * them, and keeping them in `known` lets a re-typed placeholder get its settings back.
 */
export function variablesForText(
  text: string,
  known: Readonly<Record<string, PromptVariable>>,
): PromptVariable[] {
  return variableNames(text).map((name) => known[name] ?? defaultVariable(name))
}

/** A title turned into a command name candidate: lower case, umlauts spelled out, hyphens. */
export function suggestPromptName(title: string): string {
  return title
    .toLowerCase()
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/ß/g, 'ss')
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, PROMPT_NAME_MAX_LENGTH)
    .replace(/-+$/g, '')
}

export interface PromptDraft {
  name: string
  title: string
  description: string
  text: string
  variables: PromptVariable[]
}

function validateVariable(variable: PromptVariable, errors: string[]) {
  const { name } = variable
  if (!variable.label.trim() || variable.label.length > LABEL_MAX_LENGTH) {
    errors.push(`Die Variable „${name}“ braucht eine Beschriftung mit höchstens 255 Zeichen.`)
  }
  const options = variable.options ?? []
  if (variable.type === 'SELECT') {
    if (options.length === 0 || options.length > PROMPT_MAX_OPTIONS) {
      errors.push(`Die Auswahl „${name}“ braucht zwischen 1 und 50 Auswahlwerte.`)
    }
    if (options.some((option) => !option.trim() || option.length > OPTION_MAX_LENGTH)) {
      errors.push(
        `Ein Auswahlwert der Variable „${name}“ ist leer oder länger als ${OPTION_MAX_LENGTH} Zeichen.`,
      )
    }
    const seen = new Set<string>()
    for (const option of options) {
      if (seen.has(option)) {
        errors.push(`Der Auswahlwert „${option}“ steht mehrfach in der Variable „${name}“.`)
        break
      }
      seen.add(option)
    }
  }
  const value = variable.defaultValue
  if (value && value.length > DEFAULT_MAX_LENGTH) {
    errors.push(
      `Die Vorbelegung der Variable „${name}“ ist länger als ${DEFAULT_MAX_LENGTH} Zeichen.`,
    )
  } else if (value) {
    if (variable.type === 'SELECT' && !options.includes(value)) {
      errors.push(`Die Vorbelegung der Auswahl „${name}“ ist keiner ihrer Auswahlwerte.`)
    }
    if (variable.type === 'DATE' && !DATE_PATTERN.test(value)) {
      errors.push(
        `Die Vorbelegung der Datumsvariable „${name}“ ist kein Datum im Format JJJJ-MM-TT.`,
      )
    }
  }
}

/**
 * The client-side checks of a prompt, worded like the server's refusals. An empty list means the
 * draft may be sent; the server's own answer is shown as well when it still refuses.
 */
export function validatePromptDraft(draft: PromptDraft): string[] {
  const errors: string[] = []
  if (!PROMPT_NAME_PATTERN.test(draft.name) || draft.name.length > PROMPT_NAME_MAX_LENGTH) {
    errors.push(
      'Der Name eines Prompts besteht aus Kleinbuchstaben, Ziffern und einzelnen Bindestrichen, höchstens 64 Zeichen.',
    )
  }
  if (!draft.title.trim() || draft.title.length > PROMPT_TITLE_MAX_LENGTH) {
    errors.push('Der Titel ist erforderlich und hat höchstens 255 Zeichen.')
  }
  if (draft.description.length > PROMPT_DESCRIPTION_MAX_LENGTH) {
    errors.push('Die Beschreibung hat höchstens 2000 Zeichen.')
  }
  if (!draft.text.trim() || draft.text.length > PROMPT_TEXT_MAX_LENGTH) {
    errors.push('Der Text ist erforderlich und hat höchstens 8000 Zeichen.')
  }
  for (const segment of splitPromptText(draft.text)) {
    if (segment.kind === 'invalid') {
      errors.push(
        `Ungültiger Platzhalter „{{${segment.value.slice(2, -2).trim()}}}“: Ein Variablenname beginnt mit einem Buchstaben und enthält nur Buchstaben, Ziffern und Unterstriche.`,
      )
    }
  }
  if (draft.variables.length > PROMPT_MAX_VARIABLES) {
    errors.push('Ein Prompt kann höchstens 20 Variablen haben.')
  }
  draft.variables.forEach((variable) => validateVariable(variable, errors))
  return errors
}

/** Whether a text field for this type spans several lines. */
export function isMultilineType(type: PromptVariableType): boolean {
  return type === 'TEXTAREA'
}

/** Today as `yyyy-MM-dd` in the person's own time zone - the value of a date field. */
export function todayIso(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/** `yyyy-MM-dd` as the German `TT.MM.JJJJ` a resolved text carries; anything else unchanged. */
export function germanDate(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  return match ? `${match[3]}.${match[2]}.${match[1]}` : iso
}

/**
 * The starting values of the insertion form: the defined default, and for a date field without one
 * the current day - a date is almost always "as of today".
 */
export function initialValues(
  variables: PromptVariable[],
  now: Date = new Date(),
): Record<string, string> {
  const values: Record<string, string> = {}
  for (const variable of variables) {
    values[variable.name] = variable.defaultValue ?? (variable.type === 'DATE' ? todayIso(now) : '')
  }
  return values
}

/** Whether every required variable has a value - the condition for inserting. */
export function isComplete(
  variables: PromptVariable[],
  values: Readonly<Record<string, string>>,
): boolean {
  return variables.every((variable) => !variable.required || (values[variable.name] ?? '').trim())
}

export interface PromptResolution {
  /** What `{{USER_NAME}}` becomes. */
  userName: string
  /** The day `{{CURRENT_DATE}}` stands for; today when absent. */
  now?: Date
  /**
   * The preview's rule for an empty value: the default, else the label in brackets, so an unfilled
   * variable stays visible. Inserting leaves it empty - the form already carried the default.
   */
  showUnfilled?: boolean
}

/**
 * The text with every placeholder replaced - the one resolution behind both the editor's preview
 * and the chat's insertion: own variables by their values, a date as `TT.MM.JJJJ`, `{{CURRENT_DATE}}`
 * by the day and `{{USER_NAME}}` by the person's name. An invalid placeholder stays as written.
 */
export function resolvePromptText(
  text: string,
  variables: PromptVariable[],
  values: Readonly<Record<string, string>>,
  { userName, now, showUnfilled = false }: PromptResolution,
): string {
  const byName = new Map(variables.map((variable) => [variable.name, variable]))
  return splitPromptText(text)
    .map((segment) => {
      if (segment.kind === 'system') {
        return segment.name === 'CURRENT_DATE' ? germanDate(todayIso(now)) : userName
      }
      if (segment.kind !== 'variable') return segment.value
      const variable = byName.get(segment.name)
      let value = values[segment.name] ?? ''
      if (!value && showUnfilled) {
        value = variable?.defaultValue ?? ''
        if (!value) return `[${variable?.label || segment.name}]`
      }
      return variable?.type === 'DATE' ? germanDate(value) : value
    })
    .join('')
}

const PROMPT_FIELD_LABELS: Record<string, string> = {
  name: 'Befehl',
  title: 'Titel',
  description: 'Beschreibung',
  text: 'Text',
  variables: 'Variablen',
  sortOrder: 'Reihenfolge',
}

const VARIABLE_FIELD_LABELS: Record<string, string> = {
  name: 'Name',
  label: 'Beschriftung',
  type: 'Typ',
  required: 'Pflicht',
  defaultValue: 'Vorbelegung',
  options: 'Auswahlwerte',
}

/**
 * The German name of a field the server refused, from its API path (`variables[0].label` becomes
 * „Variable „frist“ – Beschriftung“). An unknown path yields „Eingabe“ rather than English.
 */
export function promptFieldLabel(field: string, variables: PromptVariable[]): string {
  const variableMatch = /^variables\[(\d+)\](?:\.(\w+))?/.exec(field)
  if (variableMatch) {
    const variable = variables[Number(variableMatch[1])]
    const which = variable ? `Variable „${variable.name}“` : 'Variable'
    const part = variableMatch[2] ? VARIABLE_FIELD_LABELS[variableMatch[2]] : undefined
    return part ? `${which} – ${part}` : which
  }
  return PROMPT_FIELD_LABELS[field] ?? 'Eingabe'
}
