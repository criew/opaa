import { describe, expect, it } from 'vitest'
import type { AvailablePrompt, PromptVariableDefinition } from '../../types/api'
import {
  findActiveSlashCommand,
  germanDate,
  initialValues,
  isComplete,
  matchPrompts,
  resolvePromptText,
  todayIso,
} from './promptTemplate'

const stichtag: PromptVariableDefinition = {
  name: 'stichtag',
  label: 'Stichtag',
  type: 'DATE',
  required: true,
}
const umfang: PromptVariableDefinition = {
  name: 'umfang',
  label: 'Umfang',
  type: 'SELECT',
  required: false,
  defaultValue: 'kurz',
  options: ['kurz', 'ausführlich'],
}
const vermerk: PromptVariableDefinition = {
  name: 'vermerk',
  label: 'Vermerk',
  type: 'TEXT',
  required: true,
}

function available(name: string, title: string, description?: string): AvailablePrompt {
  return {
    id: name,
    libraryId: 'library',
    libraryName: 'Bibliothek',
    name,
    title,
    description: description ?? null,
    hasVariables: false,
    associatedWithSpace: false,
  }
}

describe('findActiveSlashCommand', () => {
  it('opens only for a slash at the start of a line', () => {
    expect(findActiveSlashCommand('/zusam', 6)).toEqual({ start: 0, query: 'zusam' })
    expect(findActiveSlashCommand('Erste Zeile\n/an', 15)).toEqual({ start: 12, query: 'an' })
    expect(findActiveSlashCommand('siehe /etc', 10)).toBeNull()
    expect(findActiveSlashCommand('/zusam fassen', 13)).toBeNull()
  })
})

describe('matchPrompts', () => {
  const prompts = [
    available('zusammenfassung', 'Zusammenfassung'),
    available('anhoerung', 'Anhörungsschreiben', 'Entwurf nach VwVfG'),
  ]

  it('searches command name, title and description, keeping the order', () => {
    expect(matchPrompts(prompts, 'zusam').map((p) => p.name)).toEqual(['zusammenfassung'])
    expect(matchPrompts(prompts, 'schreiben').map((p) => p.name)).toEqual(['anhoerung'])
    expect(matchPrompts(prompts, 'vwvfg').map((p) => p.name)).toEqual(['anhoerung'])
    expect(matchPrompts(prompts, '')).toHaveLength(2)
  })
})

describe('form values', () => {
  const now = new Date(2026, 8, 24, 10, 30)

  it('prefills defaults and a date without default with today', () => {
    expect(todayIso(now)).toBe('2026-09-24')
    expect(initialValues([stichtag, umfang, vermerk], now)).toEqual({
      stichtag: '2026-09-24',
      umfang: 'kurz',
      vermerk: '',
    })
  })

  it('is complete only once every required field has a value', () => {
    expect(isComplete([stichtag, vermerk], { stichtag: '2026-09-24', vermerk: ' ' })).toBe(false)
    expect(isComplete([stichtag, vermerk], { stichtag: '2026-09-24', vermerk: 'A-1' })).toBe(true)
    expect(isComplete([umfang], { umfang: '' })).toBe(true)
  })
})

describe('resolvePromptText', () => {
  it('fills variables, writes dates in German and resolves the system variables', () => {
    const text = resolvePromptText(
      'Stand {{stichtag}} ({{umfang}}), erstellt {{CURRENT_DATE}} von {{USER_NAME}}.',
      [stichtag, umfang],
      { stichtag: '2026-09-01', umfang: 'ausführlich' },
      { userName: 'Erika Muster', now: new Date(2026, 8, 24) },
    )

    expect(text).toBe('Stand 01.09.2026 (ausführlich), erstellt 24.09.2026 von Erika Muster.')
  })

  it('leaves an optional empty variable empty and an unknown placeholder untouched', () => {
    expect(
      resolvePromptText('A{{umfang}}B {{fremd}}', [umfang], { umfang: '' }, { userName: '' }),
    ).toBe('AB {{fremd}}')
    expect(germanDate('kein Datum')).toBe('kein Datum')
  })
})
