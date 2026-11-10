import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Autocomplete from '@mui/material/Autocomplete'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import { ThemeProvider } from '@mui/material/styles'
import type { ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from '../../theme/theme'
import { formWidth } from '../../theme/tokens'
import FormColumn from './FormColumn'
import FormField from './FormField'
import FormFieldRow from './FormFieldRow'

function renderInTheme(ui: ReactElement) {
  return render(<ThemeProvider theme={createAppTheme('light')}>{ui}</ThemeProvider>)
}

describe('FormField', () => {
  it('puts the label above the field and ties it to the input', () => {
    renderInTheme(<FormField label="Server-Adresse" defaultValue="smtp.example" />)
    const input = screen.getByLabelText('Server-Adresse')
    expect(input).toHaveValue('smtp.example')
    const label = screen.getByText('Server-Adresse')
    expect(label.tagName).toBe('LABEL')
    expect(label).toHaveAttribute('for', input.id)
    // Above the field, not floating inside it: MUI's own label is not rendered at all.
    expect(document.querySelector('.MuiInputLabel-root')).toBeNull()
    expect(label.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('draws no asterisk on a required field and marks the input as required', () => {
    renderInTheme(<FormField label="Name" required />)
    expect(screen.getByLabelText('Name')).toBeRequired()
    expect(document.body.textContent).not.toContain('*')
  })

  it('appends „(optional)“ to the label of an optional field', () => {
    renderInTheme(<FormField label="Beschreibung" optional />)
    expect(screen.getByLabelText('Beschreibung (optional)')).toBeInTheDocument()
  })

  it('names a select by its label and offers its options', async () => {
    renderInTheme(
      <FormField select label="Verschlüsselung" defaultValue="tls">
        <MenuItem value="tls">TLS</MenuItem>
        <MenuItem value="none">keine</MenuItem>
      </FormField>,
    )
    const select = screen.getByRole('combobox', { name: 'Verschlüsselung' })
    expect(screen.getByLabelText('Verschlüsselung')).toBe(select)
    await userEvent.click(select)
    expect(within(screen.getByRole('listbox')).getAllByRole('option')).toHaveLength(2)
  })

  it('labels the input of an Autocomplete', () => {
    renderInTheme(
      <Autocomplete
        options={['A', 'B']}
        renderInput={(params) => <FormField {...params} label="Vorlage" />}
      />,
    )
    expect(screen.getByRole('combobox', { name: 'Vorlage' })).toBe(screen.getByLabelText('Vorlage'))
  })

  it('shows no label on a filter field and names it through aria-label', () => {
    renderInTheme(
      <>
        <FormField hideLabel label="Konten suchen" placeholder="Name oder E-Mail-Adresse …" />
        <FormField hideLabel select label="Zustand" defaultValue="ALL">
          <MenuItem value="ALL">Alle Zustände</MenuItem>
        </FormField>
      </>,
    )
    expect(screen.queryByText('Konten suchen')).toBeNull()
    expect(screen.queryByText('Zustand')).toBeNull()
    expect(screen.getByRole('textbox', { name: 'Konten suchen' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Zustand' })).toHaveTextContent('Alle Zustände')
  })

  it('ties helper text and error to the input, flush with the left edge of the field', () => {
    renderInTheme(<FormField label="Port" error helperText="Bitte eine Zahl eingeben." />)
    const input = screen.getByLabelText('Port')
    expect(input).toHaveAccessibleDescription('Bitte eine Zahl eingeben.')
    expect(input).toBeInvalid()
    const helper = getComputedStyle(screen.getByText('Bitte eine Zahl eingeben.'))
    expect(helper.marginLeft).toBe('0px')
    expect(helper.marginRight).toBe('0px')
  })

  it('gives a number field a box of 160 px and leaves label and helper the column', () => {
    renderInTheme(
      <>
        <FormField label="Tage" type="number" />
        <FormField label="Port" numeric fullWidth={false} />
        <FormField label="Adresse" />
      </>,
    )
    const box = (name: string) =>
      getComputedStyle(screen.getByLabelText(name).closest('.MuiInputBase-root') as HTMLElement)
    const field = (name: string) =>
      getComputedStyle(
        (screen.getByLabelText(name).closest('.MuiInputBase-root') as HTMLElement).parentElement
          ?.parentElement as HTMLElement,
      )
    expect(box('Tage').width).toBe(`${formWidth.number}px`)
    expect(field('Tage').width).toBe('100%')
    expect(field('Port').width).toBe(`${formWidth.number}px`)
    expect(box('Adresse').width).not.toBe(`${formWidth.number}px`)
  })
})

describe('FormColumn and FormFieldRow', () => {
  it('limits a settings form to one column of 640 px', () => {
    renderInTheme(
      <FormColumn data-testid="form">
        <FormField label="Adresse" />
      </FormColumn>,
    )
    expect(formWidth.column).toBe(640)
    const column = getComputedStyle(screen.getByTestId('form'))
    expect(column.maxWidth).toBe('640px')
    expect(column.flexDirection).toBe('column')
  })

  it('moves a button beside a field down by the height of the label', () => {
    renderInTheme(
      <FormFieldRow>
        <FormField label="Neues Linkziel" />
        <Button>Hinzufügen</Button>
      </FormFieldRow>,
    )
    const label = getComputedStyle(screen.getByText('Neues Linkziel'))
    const labelBlock = parseFloat(label.lineHeight) + parseFloat(label.marginBottom)
    expect(labelBlock).toBe(23)
    const rules = Array.from(document.styleSheets)
      .flatMap((sheet) => Array.from(sheet.cssRules))
      .map((rule) => rule.cssText)
      .join('\n')
    expect(rules).toMatch(/>\s*:not\(\.OpaaFormField-labelled\)\s*\{\s*margin-top: 23px/)
    expect(rules).toMatch(/align-items: flex-start/)
  })
})

/**
 * The application's source text, read in by Vite (`?raw`). Everything outside this folder builds
 * its fields from `FormField`; the folder itself is the one place that may hand a label to MUI.
 */
const SOURCES = Object.fromEntries(
  Object.entries(
    import.meta.glob('../../../**/*.tsx', {
      query: '?raw',
      import: 'default',
      eager: true,
    }) as Record<string, string>,
  ).filter(([path]) => !path.includes('/shared/ui/form/') && !/\.test\.tsx$/.test(path)),
)

/** MUI's field components: a `label` on any of them draws the floating label. */
const MUI_FIELDS = [
  'TextField',
  'Select',
  'NativeSelect',
  'Autocomplete',
  'FormControl',
  'OutlinedInput',
  'DatePicker',
  'DateField',
  'DateTimePicker',
  'TimePicker',
]

/**
 * The attribute names of every opening tag of the given components. Reads the tag up to its
 * closing `>` and skips whatever stands inside braces or quotes, so that a `label=` of a nested
 * element or inside an expression does not count.
 */
function attributesOf(
  source: string,
  components: string[],
): Array<{ tag: string; names: string[] }> {
  const found: Array<{ tag: string; names: string[] }> = []
  const opening = new RegExp(`<(${components.join('|')})(?=[\\s/>])`, 'g')
  for (let match = opening.exec(source); match; match = opening.exec(source)) {
    let depth = 0
    let quote = ''
    let top = ''
    for (let i = match.index + match[0].length; i < source.length; i++) {
      const char = source[i]
      if (quote) {
        if (char === quote) quote = ''
      } else if (char === '{') depth++
      else if (char === '}') depth--
      else if (depth === 0 && (char === '"' || char === "'")) quote = char
      else if (depth === 0 && char === '>') break
      else if (depth === 0) top += char
    }
    found.push({ tag: match[1], names: Array.from(top.matchAll(/([\w-]+)=/g), (m) => m[1]) })
  }
  return found
}

describe('form fields in the source text (guidelines 5.2)', () => {
  it('reads the application and finds the fields', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(300)
    expect(
      Object.values(SOURCES).filter((source) => /<FormField[\s>]/.test(source)).length,
    ).toBeGreaterThan(50)
  })

  it('recognises a label on a MUI field, and only there', () => {
    const sample = `
      <TextField size="small" label="Name" />
      <Select value={x} onChange={(e) => set({ label: e.target.value })} label={l}>
      <TextField slotProps={{ input: { endAdornment: <Chip label="x" /> } }} />
      <FormControlLabel label="Schalter" control={<Switch />} />`
    expect(attributesOf(sample, MUI_FIELDS).map((tag) => tag.names.includes('label'))).toEqual([
      true,
      true,
      false,
    ])
  })

  it('sets no label on a MUI field outside shared/ui/form', () => {
    const offenders = Object.entries(SOURCES).flatMap(([path, source]) =>
      attributesOf(source, MUI_FIELDS)
        .filter((tag) => tag.names.includes('label'))
        .map((tag) => `${path}: <${tag.tag} label=…>`),
    )
    expect(offenders).toEqual([])
  })

  it('uses no floating label of MUI outside shared/ui/form', () => {
    const offenders = Object.entries(SOURCES)
      .filter(([, source]) =>
        /@mui\/material\/InputLabel|\bInputLabel\b[^'\n]*from '@mui/.test(source),
      )
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })

  it('marks no field with an asterisk', () => {
    // „Name *“ as a string or as JSX text; a required field carries no mark, an optional one
    // „(optional)“.
    const asterisk = /[\wäöüÄÖÜß)}] \*\s*(['"`<]|\{)|['"`] \*['"`]/
    const offenders = Object.entries(SOURCES).flatMap(([path, source]) =>
      source
        .split('\n')
        .filter((line) => asterisk.test(line) && !/^\s*(\/\/|\*|\/\*)/.test(line))
        .map((line) => `${path}: ${line.trim()}`),
    )
    expect(offenders).toEqual([])
  })
})
