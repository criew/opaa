import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import Autocomplete from '@mui/material/Autocomplete'
import FormControl from '@mui/material/FormControl'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import TextField from '@mui/material/TextField'
import type { ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from './theme'
import { darkRoles, lightRoles } from './tokens'
import { contrastRatio } from '../utils/contrast'

/** WCAG 1.4.11: a field's outline and focus indicator need 3:1 against the field surface. */
const NON_TEXT_CONTRAST_MINIMUM = 3

/** Guidelines 4.4/5.2: an outlined field shows focus through its own 2px outline only. */
const FOCUSED_OUTLINE_WIDTH = '2px'

/** `#CE2C31` → `rgb(206, 44, 49)`: jsdom returns computed colours in this form. */
function asRgb(hex: string): string {
  const value = Number.parseInt(hex.replace('#', ''), 16)
  return `rgb(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255})`
}

const FIELDS: Record<string, (error: boolean) => ReactElement> = {
  TextField: (error) => <TextField label="Adresse" error={error} helperText="Hinweis" />,
  Select: (error) => (
    <FormControl error={error}>
      <Select value="a" inputProps={{ 'aria-label': 'Auswahl' }}>
        <MenuItem value="a">A</MenuItem>
      </Select>
    </FormControl>
  ),
  Autocomplete: (error) => (
    <Autocomplete
      options={['A', 'B']}
      renderInput={(params) => <TextField {...params} label="Suche" error={error} />}
    />
  ),
}

/** Renders the field, focuses its control and returns the outlined root. */
function renderFocused(mode: 'light' | 'dark', name: string, error: boolean): HTMLElement {
  render(<ThemeProvider theme={createAppTheme(mode)}>{FIELDS[name](error)}</ThemeProvider>)
  const control = screen.getByRole(name === 'TextField' ? 'textbox' : 'combobox')
  act(() => control.focus())
  const root = control.closest('.MuiOutlinedInput-root') as HTMLElement
  expect(root).toHaveClass('Mui-focused')
  return root
}

function outlineOf(root: HTMLElement): CSSStyleDeclaration {
  return getComputedStyle(root.querySelector('.MuiOutlinedInput-notchedOutline') as HTMLElement)
}

describe.each([
  ['light', lightRoles],
  ['dark', darkRoles],
] as const)('field in error under focus (%s scheme)', (mode, roles) => {
  it.each(Object.keys(FIELDS))('%s keeps the danger outline and a danger focus ring', (name) => {
    const root = renderFocused(mode, name, true)
    expect(root).toHaveClass('Mui-error')
    const outline = outlineOf(root)

    expect(outline.borderColor).toBe(asRgb(roles.danger))
    // Focus stays visible on the error outline: wider than the 1px resting line.
    expect(outline.borderWidth).toBe(FOCUSED_OUTLINE_WIDTH)
    // No ring outside the field: it would run through the floating label.
    expect(getComputedStyle(root).outline).toBe('')
  })

  it.each(Object.keys(FIELDS))(
    '%s shows focus as a 2px accent outline without an error',
    (name) => {
      const root = renderFocused(mode, name, false)
      const outline = outlineOf(root)
      expect(outline.borderColor).toBe(asRgb(roles.accent))
      expect(outline.borderWidth).toBe(FOCUSED_OUTLINE_WIDTH)
      expect(getComputedStyle(root).outline).toBe('')
    },
  )

  it('draws no ring across the floating label of a field reached with Tab', async () => {
    render(
      <ThemeProvider theme={createAppTheme(mode)}>
        <CssBaseline />
        <TextField label="Produktname" defaultValue="OPAA" />
      </ThemeProvider>,
    )
    await userEvent.tab()
    const input = screen.getByLabelText('Produktname')
    expect(input).toHaveFocus()
    const root = input.closest('.MuiOutlinedInput-root') as HTMLElement
    const label = screen.getByText('Produktname', { selector: 'label' })
    expect(label).toHaveAttribute('data-shrink', 'true')

    // The label sits on the top edge of the field; anything drawn around the root crosses it.
    expect(getComputedStyle(root).outline).toBe('')
    expect(getComputedStyle(root).boxShadow).not.toMatch(/0 0 0 [1-9]/)
    expect(outlineOf(root).borderWidth).toBe(FOCUSED_OUTLINE_WIDTH)
    expect(outlineOf(root).borderColor).toBe(asRgb(roles.accent))
  })

  it('leaves a disabled field in error with the grey disabled outline', () => {
    render(
      <ThemeProvider theme={createAppTheme(mode)}>
        <TextField label="Adresse" error disabled helperText="Hinweis" />
      </ThemeProvider>,
    )
    const root = screen.getByRole('textbox').closest('.MuiOutlinedInput-root') as HTMLElement
    expect(root).toHaveClass('Mui-error')
    expect(root).toHaveClass('Mui-disabled')

    expect(outlineOf(root).borderColor).toBe(createAppTheme(mode).palette.action.disabled)
  })

  it('keeps the floating label in danger under focus', () => {
    renderFocused(mode, 'TextField', true)

    expect(getComputedStyle(screen.getByText('Adresse', { selector: 'label' })).color).toBe(
      asRgb(roles.danger),
    )
  })

  it('draws the error outline at 3:1 or more against the field surface', () => {
    // The field surface is bg-1 in light and one step up (bg-2) in dark (guidelines 5.2).
    const surface = mode === 'dark' ? roles.bg2 : roles.bg1
    expect(contrastRatio(roles.danger, surface)).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST_MINIMUM)
  })
})
