import type { PromptVariableType } from '@/shared/api/types'

const promptVariableTypeLabels: Record<PromptVariableType, string> = {
  TEXT: 'Text (eine Zeile)',
  TEXTAREA: 'Text (mehrere Zeilen)',
  SELECT: 'Auswahl',
  DATE: 'Datum',
}

/** Render order of the variable types in the prompt editor. */
export const promptVariableTypes = Object.keys(promptVariableTypeLabels) as PromptVariableType[]

export function promptVariableTypeLabel(type: PromptVariableType | string | undefined): string {
  if (!type) return ''
  return promptVariableTypeLabels[type as PromptVariableType] ?? type
}
