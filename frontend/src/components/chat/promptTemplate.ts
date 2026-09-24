import type { AvailablePrompt, PromptVariableDefinition } from '../../types/api'

/** A placeholder exactly as the server accepts it: `{{name}}`, no whitespace. */
const PLACEHOLDER = /\{\{([A-Za-z][A-Za-z0-9_]*)\}\}/g

export interface ActiveSlashCommand {
  /** Index of the '/' that opened the command - always the start of a line. */
  start: number
  /** Text typed after '/', used to filter the prompts. */
  query: string
}

/**
 * Finds a slash command being typed at the cursor: a '/' at the very start of the cursor's line,
 * followed by no whitespace. Anywhere else a '/' is plain text.
 */
export function findActiveSlashCommand(text: string, cursor: number): ActiveSlashCommand | null {
  const lineStart = text.lastIndexOf('\n', cursor - 1) + 1
  const fragment = text.slice(lineStart, cursor)
  if (!/^\/\S*$/.test(fragment)) return null
  return { start: lineStart, query: fragment.slice(1) }
}

/** The prompts matching `query` in command name, title or description, in the given order. */
export function matchPrompts(prompts: AvailablePrompt[], query: string): AvailablePrompt[] {
  const needle = query.toLowerCase()
  if (!needle) return prompts
  return prompts.filter(
    (prompt) =>
      prompt.name.toLowerCase().includes(needle) ||
      prompt.title.toLowerCase().includes(needle) ||
      (prompt.description ?? '').toLowerCase().includes(needle),
  )
}

/** The id of the '/' option at `index` - the input's `aria-activedescendant` points at it. */
export function promptOptionId(listboxId: string, index: number): string {
  return `${listboxId}-option-${index}`
}

/** Today as `yyyy-MM-dd` in the person's own time zone - the value of a date field. */
export function todayIso(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/** `yyyy-MM-dd` as the German `TT.MM.JJJJ` the inserted text carries; anything else unchanged. */
export function germanDate(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  return match ? `${match[3]}.${match[2]}.${match[1]}` : iso
}

/**
 * The form's starting values: the defined default, and for a date field without one the current
 * day - a date is almost always "as of today".
 */
export function initialValues(
  variables: PromptVariableDefinition[],
  now: Date = new Date(),
): Record<string, string> {
  const values: Record<string, string> = {}
  for (const variable of variables) {
    values[variable.name] = variable.defaultValue ?? (variable.type === 'DATE' ? todayIso(now) : '')
  }
  return values
}

/** Whether every required variable has a value - the condition for "Einsetzen". */
export function isComplete(
  variables: PromptVariableDefinition[],
  values: Record<string, string>,
): boolean {
  return variables.every((variable) => !variable.required || (values[variable.name] ?? '').trim())
}

/**
 * The prompt text with every placeholder replaced: the defined variables by their values (a date
 * in German notation), `{{CURRENT_DATE}}` by today and `{{USER_NAME}}` by the person's name. An
 * optional variable left empty disappears.
 */
export function resolvePromptText(
  text: string,
  variables: PromptVariableDefinition[],
  values: Record<string, string>,
  context: { userName: string; now?: Date },
): string {
  const types = new Map(variables.map((variable) => [variable.name, variable.type]))
  return text.replace(PLACEHOLDER, (placeholder, name: string) => {
    if (name === 'CURRENT_DATE') return germanDate(todayIso(context.now))
    if (name === 'USER_NAME') return context.userName
    if (!types.has(name)) return placeholder
    const value = values[name] ?? ''
    return types.get(name) === 'DATE' ? germanDate(value) : value
  })
}
