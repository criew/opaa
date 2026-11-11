import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { useTheme } from '@mui/material/styles'
import { renderWithProviders } from '@/test/test-utils'
import { setViewportWidth } from '@/test/viewport'
import { DIALOG_MAX_WIDTH, type DialogWidth } from './dialogWidth'
import FormDialog from './FormDialog'
import ViewDialog from './ViewDialog'

function BreakpointProbe({ onValues }: { onValues: (values: Record<string, number>) => void }) {
  onValues(useTheme().breakpoints.values)
  return null
}

describe('dialog widths', () => {
  const originalMatchMedia = window.matchMedia

  afterEach(() => {
    window.matchMedia = originalMatchMedia
  })

  it('knows exactly three widths: 444 px confirmation, 600 px form, 900 px editor', () => {
    expect(DIALOG_MAX_WIDTH).toEqual({ confirm: 'xs', form: 'sm', editor: 'md' })

    let values: Record<string, number> = {}
    renderWithProviders(<BreakpointProbe onValues={(read) => (values = read)} />)
    // MUI caps `xs` at 444 px; `sm` and `md` are the breakpoints themselves.
    expect(Math.max(values.xs, 444)).toBe(444)
    expect(values.sm).toBe(600)
    expect(values.md).toBe(900)
  })

  it.each<[DialogWidth, string]>([
    ['confirm', 'MuiDialog-paperWidthXs'],
    ['form', 'MuiDialog-paperWidthSm'],
    ['editor', 'MuiDialog-paperWidthMd'],
  ])('gives a %s dialog its width', (width, paperClass) => {
    setViewportWidth(1280)
    renderWithProviders(
      <FormDialog
        title="Wert ergänzen"
        submitLabel="Ergänzen"
        width={width}
        onSubmit={vi.fn()}
        onClose={vi.fn()}
      >
        Inhalt
      </FormDialog>,
    )

    const dialog = screen.getByRole('dialog', { name: 'Wert ergänzen' })
    expect(dialog).toHaveClass(paperClass)
    expect(dialog).not.toHaveClass('MuiDialog-paperFullScreen')
  })

  it.each([
    [
      'form',
      <FormDialog title="Vorschau" submitLabel="Ergänzen" onSubmit={vi.fn()} onClose={vi.fn()}>
        Inhalt
      </FormDialog>,
    ],
    [
      'view',
      <ViewDialog title="Vorschau" onClose={vi.fn()}>
        Inhalt
      </ViewDialog>,
    ],
  ])('fills the screen below 600 px viewport width as a %s', (_kind, dialog) => {
    setViewportWidth(599)
    renderWithProviders(dialog)

    expect(screen.getByRole('dialog', { name: 'Vorschau' })).toHaveClass(
      'MuiDialog-paperFullScreen',
    )
  })

  it('keeps its width at exactly 600 px', () => {
    setViewportWidth(600)
    renderWithProviders(
      <ViewDialog title="Vorschau" onClose={vi.fn()}>
        Inhalt
      </ViewDialog>,
    )

    expect(screen.getByRole('dialog', { name: 'Vorschau' })).not.toHaveClass(
      'MuiDialog-paperFullScreen',
    )
  })
})
