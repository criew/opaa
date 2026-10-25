import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../../test/test-utils'
import type { BrowseResult, SelectionItem, SelectionSource } from '../sourceKit/selection'
import ScopePicker from './ScopePicker'

function spaces(count: number) {
  return Array.from({ length: count }, (_, i) => ({
    key: `S${i + 1}`,
    name: `Space ${i + 1}`,
    selectable: true,
    openable: false,
  }))
}

function source(list: () => Promise<BrowseResult>): SelectionSource {
  return {
    rootLabel: 'Spaces',
    noun: ['Space', 'Spaces'],
    list: vi.fn(list),
    serverSearch: () => false,
    searchLabel: () => 'Spaces suchen',
    nested: false,
  }
}

const listed = (count: number) =>
  source(async () => ({ entries: spaces(count), complete: true, message: null }))

function Harness({
  src,
  everything,
  onChange = vi.fn(),
}: {
  src: SelectionSource
  everything?: boolean
  onChange?: (items: SelectionItem[]) => void
}) {
  const [value, setValue] = useState<SelectionItem[]>([])
  const [all, setAll] = useState(everything)
  return (
    <ScopePicker
      id="scope"
      source={src}
      value={value}
      onChange={(items) => {
        setValue(items)
        onChange(items)
      }}
      all={all === undefined ? undefined : { active: all, onChange: setAll }}
      loadKey="k"
    />
  )
}

describe('ScopePicker', () => {
  it('stands up to seven flat entries as checkboxes in the row', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithProviders(<Harness src={listed(7)} onChange={onChange} />)

    const group = await screen.findByRole('group', { name: 'Spaces' })
    expect(within(group).getAllByRole('checkbox')).toHaveLength(7)
    expect(screen.queryByRole('button', { name: 'Spaces auswählen …' })).not.toBeInTheDocument()

    await user.click(within(group).getByRole('checkbox', { name: 'Space 3' }))
    expect(onChange).toHaveBeenLastCalledWith([{ key: 'S3', name: 'Space 3', trail: [] }])
  })

  it('leads eight entries through the dialog and lists the chosen ones in the row', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness src={listed(8)} />)

    await user.click(await screen.findByRole('button', { name: 'Spaces auswählen …' }))
    expect(screen.queryByRole('group', { name: 'Spaces' })).not.toBeInTheDocument()
    const dialog = await screen.findByRole('dialog', { name: 'Spaces auswählen' })
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Space 8' }))
    await user.click(within(dialog).getByRole('button', { name: 'Übernehmen' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('1 Space')).toBeInTheDocument()
    expect(screen.getByText('Space 8')).toBeInTheDocument()
  })

  it('offers „Alle“ and „Nur ausgewählte“ and lists only for the second', async () => {
    const user = userEvent.setup()
    const src = listed(3)
    renderWithProviders(<Harness src={src} everything />)

    expect(screen.getByRole('radio', { name: 'Alle Spaces' })).toBeChecked()
    expect(src.list).not.toHaveBeenCalled()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: 'Nur ausgewählte Spaces' }))
    expect(await screen.findAllByRole('checkbox')).toHaveLength(3)
  })

  it('names a failed listing once and loads it again on „Erneut laden“', async () => {
    const user = userEvent.setup()
    let calls = 0
    const src = source(async () => {
      calls += 1
      if (calls === 1) throw new Error('HTTP 502 Bad Gateway: Zeitüberschreitung')
      return { entries: spaces(2), complete: true, message: null }
    })
    renderWithProviders(<Harness src={src} />)

    expect(await screen.findByText('Spaces ließen sich nicht laden.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Erneut laden' }))
    expect(await screen.findAllByRole('checkbox')).toHaveLength(2)
    expect(screen.queryByText('Spaces ließen sich nicht laden.')).not.toBeInTheDocument()
  })

  it('says „Keine Spaces vorhanden.“ for an empty source', async () => {
    renderWithProviders(
      <Harness src={source(async () => ({ entries: [], complete: true, message: null }))} />,
    )

    expect(await screen.findByText('Keine Spaces vorhanden.')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  describe('a listing that is not complete', () => {
    const cut = 'Confluence hat die Auflistung begrenzt.'
    const refusal = 'Das verbundene Microsoft-Konto darf sein OneDrive nicht lesen.'
    const partial = (count: number, message: string | null) =>
      source(async () => ({ entries: spaces(count), complete: false, message }))

    it('says a cut list is incomplete, with the reason, above the checkboxes in the row', async () => {
      renderWithProviders(<Harness src={partial(3, cut)} />)

      expect(await screen.findByText(`Liste unvollständig. ${cut}`)).toBeInTheDocument()
      expect(screen.getAllByRole('checkbox')).toHaveLength(3)
    })

    it('says a cut list is incomplete, with the reason, above the link into the dialog', async () => {
      renderWithProviders(<Harness src={partial(8, cut)} />)

      expect(await screen.findByText(`Liste unvollständig. ${cut}`)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Spaces auswählen …' })).toBeInTheDocument()
    })

    it('names only the reason of a refused listing, with the link into the dialog', async () => {
      renderWithProviders(<Harness src={partial(0, refusal)} />)

      expect(await screen.findByText(refusal)).toBeInTheDocument()
      expect(screen.queryByText(/Liste unvollständig/)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Spaces auswählen …' })).toBeInTheDocument()
    })

    it('says a listing without entries and reason is incomplete', async () => {
      renderWithProviders(<Harness src={partial(0, null)} />)

      expect(await screen.findByText('Liste unvollständig.')).toBeInTheDocument()
    })
  })
})
