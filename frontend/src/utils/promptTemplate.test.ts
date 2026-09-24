import { describe, expect, it } from 'vitest'
import {
  resolvePromptPreview,
  splitPromptText,
  suggestPromptName,
  validatePromptDraft,
  variablesForText,
  type PromptDraft,
} from './promptTemplate'

const validDraft: PromptDraft = {
  name: 'anhoerung',
  title: 'Anhörungsschreiben',
  description: '',
  text: 'Aktenzeichen {{aktenzeichen}}, Stand {{CURRENT_DATE}}.',
  variables: [{ name: 'aktenzeichen', label: 'Aktenzeichen', type: 'TEXT', required: true }],
}

describe('promptTemplate', () => {
  it('splits text, own variables, system variables and invalid placeholders apart', () => {
    expect(splitPromptText('A {{x}} B {{USER_NAME}} C {{kein name}} D {{')).toEqual([
      { kind: 'text', value: 'A ' },
      { kind: 'variable', value: '{{x}}', name: 'x' },
      { kind: 'text', value: ' B ' },
      { kind: 'system', value: '{{USER_NAME}}', name: 'USER_NAME' },
      { kind: 'text', value: ' C ' },
      { kind: 'invalid', value: '{{kein name}}' },
      { kind: 'text', value: ' D {{' },
    ])
  })

  it('derives one definition per used variable, keeping known settings and dropping unused ones', () => {
    const known = {
      frist: { name: 'frist', label: 'Frist', type: 'DATE' as const, required: true },
      alt: { name: 'alt', label: 'Alt', type: 'TEXT' as const, required: false },
    }
    const variables = variablesForText(
      '{{frist}} und {{neu}} und {{frist}} {{CURRENT_DATE}}',
      known,
    )
    expect(variables.map((variable) => variable.name)).toEqual(['frist', 'neu'])
    expect(variables[0]).toBe(known.frist)
    expect(variables[1]).toMatchObject({ name: 'neu', label: 'neu', type: 'TEXT', required: false })
  })

  it('accepts a valid draft', () => {
    expect(validatePromptDraft(validDraft)).toEqual([])
  })

  it('names every client-side violation in the server wording', () => {
    const errors = validatePromptDraft({
      name: 'Anhörung',
      title: ' ',
      description: '',
      text: '{{kein name}} {{current_date}} {{wahl}} {{tag}}',
      variables: [
        { name: 'current_date', label: 'x', type: 'TEXT', required: false },
        { name: 'wahl', label: '', type: 'SELECT', required: true, options: [] },
        { name: 'tag', label: 'Tag', type: 'DATE', required: false, defaultValue: '24.09.2026' },
      ],
    })
    expect(errors).toEqual([
      'Der Name eines Prompts besteht aus Kleinbuchstaben, Ziffern und einzelnen Bindestrichen, höchstens 64 Zeichen.',
      'Der Titel ist erforderlich und hat höchstens 255 Zeichen.',
      'Ungültiger Platzhalter „{{kein name}}“: Ein Variablenname beginnt mit einem Buchstaben und enthält nur Buchstaben, Ziffern und Unterstriche, ohne Leerzeichen.',
      '„current_date“ ist eine Systemvariable und wird beim Einsetzen aufgelöst; sie kann nicht definiert werden. Schreiben Sie {{CURRENT_DATE}}.',
      'Die Variable „wahl“ braucht eine Beschriftung mit höchstens 255 Zeichen.',
      'Die Auswahl „wahl“ braucht zwischen 1 und 50 Auswahlwerte.',
      'Die Vorbelegung der Datumsvariable „tag“ ist kein Datum im Format JJJJ-MM-TT.',
    ])
  })

  it('refuses a selection default that is none of its options', () => {
    expect(
      validatePromptDraft({
        ...validDraft,
        text: '{{ton}}',
        variables: [
          {
            name: 'ton',
            label: 'Ton',
            type: 'SELECT',
            required: false,
            options: ['sachlich'],
            defaultValue: 'laut',
          },
        ],
      }),
    ).toEqual(['Die Vorbelegung der Auswahl „ton“ ist keiner ihrer Auswahlwerte.'])
  })

  it('resolves the preview from example values, defaults and system values', () => {
    const text = '{{a}} {{b}} {{c}} {{CURRENT_DATE}} {{USER_NAME}}'
    const variables = [
      { name: 'a', label: 'A', type: 'TEXT' as const, required: false },
      { name: 'b', label: 'B', type: 'TEXT' as const, required: false, defaultValue: 'vorbelegt' },
      { name: 'c', label: 'Feld C', type: 'TEXT' as const, required: true },
    ]
    expect(
      resolvePromptPreview(
        text,
        variables,
        { a: 'Beispiel' },
        {
          CURRENT_DATE: '24.09.2026',
          USER_NAME: 'Andrea Vogt',
        },
      ),
    ).toBe('Beispiel vorbelegt [Feld C] 24.09.2026 Andrea Vogt')
  })

  it('suggests a command name from a title', () => {
    expect(suggestPromptName('Anhörung – Bußgeld (§ 55)')).toBe('anhoerung-bussgeld-55')
  })
})
