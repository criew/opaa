import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import IconButton from '@mui/material/IconButton'
import { renderWithProviders } from '@/test/test-utils'
import { setViewportWidth } from '@/test/viewport'
import ReadingModeDialog from './ReadingModeDialog'

const originalMatchMedia = window.matchMedia

afterEach(() => {
  window.matchMedia = originalMatchMedia
})

function renderDialog(onClose = vi.fn()) {
  renderWithProviders(
    <ReadingModeDialog
      title="rundschreiben.docx"
      onClose={onClose}
      backLabel="Zurück zur Antwort"
      titleActions={<IconButton aria-label="Original herunterladen" />}
    >
      <p>Inhalt</p>
    </ReadingModeDialog>,
  )
  return onClose
}

describe('ReadingModeDialog', () => {
  it('fills the screen, is named by its title and leads back to the answer', async () => {
    const onClose = renderDialog()
    const dialog = screen.getByRole('dialog', { name: 'rundschreiben.docx' })
    expect(dialog).toBeVisible()
    expect(screen.getByRole('heading', { level: 2, name: 'rundschreiben.docx' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Original herunterladen' })).toBeVisible()

    await userEvent.click(screen.getByRole('button', { name: 'Zurück zur Antwort' }))
    await userEvent.click(screen.getByRole('button', { name: 'Schließen' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('closes on Escape', async () => {
    const onClose = renderDialog()
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('shows the way back as a named arrow on a phone', async () => {
    setViewportWidth(360)
    const onClose = renderDialog()
    const back = screen.getByRole('button', { name: 'Zurück zur Antwort' })
    expect(back).not.toHaveTextContent('Zurück zur Antwort')
    await userEvent.click(back)
    expect(onClose).toHaveBeenCalledOnce()
  })
})
