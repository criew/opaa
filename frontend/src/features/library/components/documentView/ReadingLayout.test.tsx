import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { setViewportWidth } from '@/test/viewport'
import ReadingLayout, { COLUMN_DEFAULT_PX, COLUMN_MAX_PX, COLUMN_MIN_PX } from './ReadingLayout'

const originalMatchMedia = window.matchMedia

afterEach(() => {
  window.matchMedia = originalMatchMedia
})

const finding = { place: 'Seite 2', quote: 'Die Frist beträgt sechs Monate' }

function renderLayout(props: Partial<Parameters<typeof ReadingLayout>[0]> = {}) {
  return renderWithProviders(
    <ReadingLayout
      finding={finding}
      status={{ text: 'Fundstelle auf Seite 2 markiert.', warning: false }}
      {...props}
    >
      <div>Dokument</div>
    </ReadingLayout>,
  )
}

describe('ReadingLayout', () => {
  it('shows the card beside the document: place, quote, status and the jump', async () => {
    const onJump = vi.fn()
    renderLayout({ onJump })

    expect(screen.getByRole('heading', { name: 'Fundstelle' })).toBeInTheDocument()
    expect(screen.getByText('Seite 2')).toBeVisible()
    expect(screen.getByText('„Die Frist beträgt sechs Monate“')).toBeVisible()
    expect(screen.getByRole('status')).toHaveTextContent('Fundstelle auf Seite 2 markiert.')
    await userEvent.click(screen.getByRole('button', { name: 'Zur Fundstelle' }))
    expect(onJump).toHaveBeenCalledOnce()
  })

  it('holds exactly one status line in every arrangement', () => {
    const { unmount } = renderLayout()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    unmount()

    renderLayout({ finding: null }).unmount()
    setViewportWidth(360)
    renderLayout()
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  it('offers no jump while the passage is not marked', () => {
    renderLayout({ status: { text: 'Die Fundstelle wurde nicht gefunden.', warning: true } })
    expect(screen.queryByRole('button', { name: 'Zur Fundstelle' })).toBeNull()
    expect(screen.getByRole('status')).toHaveTextContent('nicht gefunden')
  })

  it('shows no card without a citation, but keeps the panels', () => {
    renderLayout({ finding: null, panels: <nav aria-label="Inhaltsverzeichnis">Inhalt</nav> })
    expect(screen.queryByRole('heading', { name: 'Fundstelle' })).toBeNull()
    expect(screen.getByRole('navigation', { name: 'Inhaltsverzeichnis' })).toBeInTheDocument()
  })

  describe('on a phone', () => {
    it('folds the card into one line that unfolds to the quote', async () => {
      setViewportWidth(360)
      const onJump = vi.fn()
      renderLayout({ onJump })

      const bar = screen.getByRole('region', { name: 'Fundstellen-Leiste' })
      const toggle = within(bar).getByRole('button', { name: 'Seite 2' })
      expect(toggle).toHaveAttribute('aria-expanded', 'false')
      expect(screen.queryByText('„Die Frist beträgt sechs Monate“')).toBeNull()

      await userEvent.click(toggle)
      expect(toggle).toHaveAttribute('aria-expanded', 'true')
      expect(screen.getByText('„Die Frist beträgt sechs Monate“')).toBeVisible()

      await userEvent.click(within(bar).getByRole('button', { name: 'Zur Fundstelle' }))
      expect(onJump).toHaveBeenCalledOnce()
    })

    it('keeps a warning visible below the line', () => {
      setViewportWidth(360)
      renderLayout({ status: { text: 'Das Original wurde geändert.', warning: true } })
      expect(screen.getByRole('status')).toBeVisible()
      expect(screen.getByRole('status')).not.toHaveStyle({ position: 'absolute' })
    })
  })

  describe('width separator', () => {
    it('appears only from 1440 px on', () => {
      setViewportWidth(1280)
      const { unmount } = renderLayout()
      expect(screen.queryByRole('separator')).toBeNull()
      unmount()

      setViewportWidth(1440)
      renderLayout()
      expect(screen.getByRole('separator', { name: 'Breite der Fundstellen-Spalte' })).toBeVisible()
    })

    it('sets the column width with the arrow keys, Home and End, within its bounds', () => {
      setViewportWidth(1600)
      renderLayout()
      const separator = screen.getByRole('separator')
      expect(separator).toHaveAttribute('aria-orientation', 'vertical')
      expect(separator).toHaveAttribute('aria-valuenow', String(COLUMN_DEFAULT_PX))
      expect(separator).toHaveAttribute('aria-valuemin', String(COLUMN_MIN_PX))
      expect(separator).toHaveAttribute('aria-valuemax', String(COLUMN_MAX_PX))
      expect(separator).toHaveAttribute('aria-valuetext', `${COLUMN_DEFAULT_PX} Pixel`)
      const column = document.getElementById(separator.getAttribute('aria-controls')!)!
      expect(column).toHaveStyle({ width: `${COLUMN_DEFAULT_PX}px` })

      fireEvent.keyDown(separator, { key: 'ArrowRight' })
      expect(separator).toHaveAttribute('aria-valuenow', String(COLUMN_DEFAULT_PX + 24))
      expect(column).toHaveStyle({ width: `${COLUMN_DEFAULT_PX + 24}px` })
      fireEvent.keyDown(separator, { key: 'ArrowLeft' })
      fireEvent.keyDown(separator, { key: 'ArrowLeft' })
      expect(separator).toHaveAttribute('aria-valuenow', String(COLUMN_DEFAULT_PX - 24))
      fireEvent.keyDown(separator, { key: 'End' })
      expect(separator).toHaveAttribute('aria-valuenow', String(COLUMN_MAX_PX))
      fireEvent.keyDown(separator, { key: 'ArrowRight' })
      expect(separator).toHaveAttribute('aria-valuenow', String(COLUMN_MAX_PX))
      fireEvent.keyDown(separator, { key: 'Home' })
      expect(separator).toHaveAttribute('aria-valuenow', String(COLUMN_MIN_PX))
    })

    it('follows the pointer while dragged', () => {
      setViewportWidth(1600)
      renderLayout()
      const separator = screen.getByRole('separator')
      fireEvent.pointerDown(separator, { clientX: 400, pointerId: 1 })
      fireEvent.pointerMove(separator, { clientX: 460, pointerId: 1 })
      expect(separator).toHaveAttribute('aria-valuenow', String(COLUMN_DEFAULT_PX + 60))
      fireEvent.pointerUp(separator, { pointerId: 1 })
      fireEvent.pointerMove(separator, { clientX: 600, pointerId: 1 })
      expect(separator).toHaveAttribute('aria-valuenow', String(COLUMN_DEFAULT_PX + 60))
    })
  })
})
