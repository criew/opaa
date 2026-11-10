import { render, screen } from '@testing-library/react'
import Autocomplete from '@mui/material/Autocomplete'
import MenuItem from '@mui/material/MenuItem'
import { ThemeProvider } from '@mui/material/styles'
import type { ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import FormField from '../ui/form/FormField'
import { createAppTheme } from './theme'
import { controlHeight } from './tokens'

/**
 * Guidelines 5.2: every single-line field is `controlHeight.regular` high, whatever its kind and
 * its MUI size. The outline is drawn on top of the box and adds nothing to the height.
 */
const FIELDS: Record<string, { role: string; field: (size: 'small' | 'medium') => ReactElement }> =
  {
    text: { role: 'textbox', field: (size) => <FormField label="Adresse" size={size} /> },
    date: {
      role: 'date',
      field: (size) => <FormField label="Datum" type="date" size={size} />,
    },
    select: {
      role: 'combobox',
      field: (size) => (
        <FormField select label="Auswahl" defaultValue="a" size={size}>
          <MenuItem value="a">A</MenuItem>
        </FormField>
      ),
    },
    autocomplete: {
      role: 'combobox',
      field: (size) => (
        <Autocomplete
          size={size}
          options={['A', 'B']}
          renderInput={(params) => <FormField {...params} label="Suche" />}
        />
      ),
    },
  }

function px(value: string): number {
  return value === '' ? 0 : parseFloat(value)
}

/** The height of the field's box: the control's line plus the padding around it. */
function boxHeight(control: HTMLElement): number {
  const root = getComputedStyle(control.closest('.MuiOutlinedInput-root') as HTMLElement)
  const own = getComputedStyle(control)
  const line = px(own.height) || px(own.minHeight)
  return (
    line + px(own.paddingTop) + px(own.paddingBottom) + px(root.paddingTop) + px(root.paddingBottom)
  )
}

describe.each(['light', 'dark'] as const)('height of a single-line field (%s scheme)', (mode) => {
  it.each(
    Object.keys(FIELDS).flatMap((name) => [
      [name, 'small'],
      [name, 'medium'],
    ]) as Array<[string, 'small' | 'medium']>,
  )('%s field of size %s is 36 px high', (name, size) => {
    render(<ThemeProvider theme={createAppTheme(mode)}>{FIELDS[name].field(size)}</ThemeProvider>)
    const control =
      FIELDS[name].role === 'date'
        ? screen.getByLabelText('Datum')
        : screen.getByRole(FIELDS[name].role)
    expect(controlHeight.regular).toBe(36)
    expect(boxHeight(control)).toBe(controlHeight.regular)
  })
})
