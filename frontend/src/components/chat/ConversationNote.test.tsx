import { useRef, useState } from 'react'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import type { ChatNoteItem } from '../../types/api'
import ConversationNote from './ConversationNote'
import { NOTE_MIN_COMPLETED_ROUNDS } from './conversationNoteVisibility'

const FRAME_POINT: ChatNoteItem = {
  id: 'note-1',
  text: 'Arbeitet im Bürgerbüro Nebenstelle 3',
  kind: 'RAHMEN',
  createdAt: '2026-03-08T09:00:10Z',
}
const YEAR_POINT: ChatNoteItem = {
  id: 'note-2',
  text: 'Bezugsjahr 2024',
  kind: 'RAHMEN',
  createdAt: '2026-03-08T09:01:10Z',
}
const FORM_POINT: ChatNoteItem = {
  id: 'note-3',
  text: 'Möchte knappe Antworten',
  kind: 'ANTWORTFORM',
  createdAt: '2026-03-08T09:02:10Z',
}

const TOGGLE = 'Gesprächsnotiz · 2'

/** Removal is optimistic in the store; this harness mirrors that locally, so the focus handling
 * after a removal can be observed the way it behaves in the page. */
function Harness({
  initialItems,
  completedRounds = 3,
  canRemove = true,
  onRemove,
}: {
  initialItems: ChatNoteItem[]
  completedRounds?: number
  canRemove?: boolean
  onRemove?: (itemId: string) => void
}) {
  const [items, setItems] = useState(initialItems)
  const headerRef = useRef<HTMLDivElement>(null)
  return (
    <div ref={headerRef} tabIndex={-1} data-testid="header">
      <ConversationNote
        items={items}
        completedRounds={completedRounds}
        canRemove={canRemove}
        onRemove={(itemId) => {
          onRemove?.(itemId)
          setItems((current) => current.filter((item) => item.id !== itemId))
        }}
        emptyFocusRef={headerRef}
      />
    </div>
  )
}

describe('ConversationNote (#1488)', () => {
  it('shows no button for an empty note, however many rounds are done', () => {
    renderWithProviders(<Harness initialItems={[]} completedRounds={9} />)

    expect(screen.queryByRole('button', { name: /Gesprächsnotiz/ })).not.toBeInTheDocument()
  })

  it('shows no button before the third completed round, even with points', () => {
    renderWithProviders(
      <Harness
        initialItems={[FRAME_POINT, YEAR_POINT]}
        completedRounds={NOTE_MIN_COMPLETED_ROUNDS - 1}
      />,
    )

    expect(screen.queryByRole('button', { name: /Gesprächsnotiz/ })).not.toBeInTheDocument()
  })

  it('shows the button with the number of points from the third round on, collapsed', () => {
    renderWithProviders(<Harness initialItems={[FRAME_POINT, YEAR_POINT]} completedRounds={3} />)

    expect(screen.getByRole('button', { name: TOGGLE })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: 'Gesprächsnotiz' })).not.toBeInTheDocument()
    expect(screen.queryByText('Bezugsjahr 2024')).not.toBeInTheDocument()
  })

  it('expands into a named region with the points, and never shows their kind', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Harness initialItems={[FRAME_POINT, YEAR_POINT, FORM_POINT]} completedRounds={4} />,
    )

    await user.click(screen.getByRole('button', { name: 'Gesprächsnotiz · 3' }))

    const panel = screen.getByRole('region', { name: 'Gesprächsnotiz' })
    expect(screen.getByRole('button', { name: 'Gesprächsnotiz · 3' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    expect(panel).toHaveTextContent(
      'Diese Angaben hat OPAA aus Ihren Nachrichten in diesem Chat festgehalten. Sie fließen in die nächsten Antworten ein.',
    )
    expect(screen.getByText('Arbeitet im Bürgerbüro Nebenstelle 3')).toBeVisible()
    expect(screen.getByText('Möchte knappe Antworten')).toBeVisible()
    // The kind steers which prompt a point reaches; it is not a taxonomy for the person.
    expect(panel).not.toHaveTextContent('RAHMEN')
    expect(panel).not.toHaveTextContent('ANTWORTFORM')
  })

  it('renders a long point in full - the surface wraps, it does not truncate', async () => {
    const user = userEvent.setup()
    const longText = `Arbeitet ${'sehr '.repeat(30)}lange im Bürgerbüro`
    renderWithProviders(
      <Harness initialItems={[{ ...FRAME_POINT, text: longText }]} completedRounds={3} />,
    )

    await user.click(screen.getByRole('button', { name: 'Gesprächsnotiz · 1' }))

    expect(screen.getByText(longText)).toBeVisible()
  })

  it('removes a point without asking and keeps focus on the next one', async () => {
    const user = userEvent.setup()
    const onRemove = vi.fn()
    renderWithProviders(
      <Harness initialItems={[FRAME_POINT, YEAR_POINT]} completedRounds={3} onRemove={onRemove} />,
    )

    await user.click(screen.getByRole('button', { name: TOGGLE }))
    await user.click(screen.getAllByRole('button', { name: 'Notizpunkt entfernen' })[0])

    expect(onRemove).toHaveBeenCalledWith('note-1')
    expect(screen.queryByText('Arbeitet im Bürgerbüro Nebenstelle 3')).not.toBeInTheDocument()
    const remaining = screen.getAllByRole('button', { name: 'Notizpunkt entfernen' })
    expect(remaining).toHaveLength(1)
    expect(remaining[0]).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Gesprächsnotiz · 1' })).toBeInTheDocument()
  })

  it('drops the whole surface with the last point and parks focus where it sat', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness initialItems={[FRAME_POINT]} completedRounds={5} />)

    await user.click(screen.getByRole('button', { name: 'Gesprächsnotiz · 1' }))
    await user.click(screen.getByRole('button', { name: 'Notizpunkt entfernen' }))

    expect(screen.queryByRole('button', { name: /Gesprächsnotiz/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Gesprächsnotiz' })).not.toBeInTheDocument()
    expect(screen.getByTestId('header')).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent('Die Gesprächsnotiz ist jetzt leer.')
  })

  it('shows the points without remove buttons in an archived space', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Harness initialItems={[FRAME_POINT, YEAR_POINT]} completedRounds={3} canRemove={false} />,
    )

    await user.click(screen.getByRole('button', { name: TOGGLE }))

    expect(screen.getByText('Bezugsjahr 2024')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Notizpunkt entfernen' })).not.toBeInTheDocument()
  })

  it('closes on Escape and returns focus to the button', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness initialItems={[FRAME_POINT, YEAR_POINT]} completedRounds={3} />)

    await user.click(screen.getByRole('button', { name: TOGGLE }))
    await user.click(screen.getAllByRole('button', { name: 'Notizpunkt entfernen' })[1])
    await user.keyboard('{Escape}')

    // The panel unmounts once its collapse transition has run.
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: 'Gesprächsnotiz' })).not.toBeInTheDocument(),
    )
    const toggle = screen.getByRole('button', { name: 'Gesprächsnotiz · 1' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveFocus()
  })

  it('is reachable and operable with the keyboard alone', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness initialItems={[FRAME_POINT, YEAR_POINT]} completedRounds={3} />)

    await user.tab()
    expect(screen.getByRole('button', { name: TOGGLE })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('region', { name: 'Gesprächsnotiz' })).toBeInTheDocument()
    await user.tab()
    expect(screen.getAllByRole('button', { name: 'Notizpunkt entfernen' })[0]).toHaveFocus()
    await user.keyboard(' ')

    expect(screen.queryByText('Arbeitet im Bürgerbüro Nebenstelle 3')).not.toBeInTheDocument()
  })
})
