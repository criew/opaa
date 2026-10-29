import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import PdfOutline, { type OutlineNode } from './PdfOutline'

function node(title: string, page: number | null, children: OutlineNode[] = []): OutlineNode {
  return { title, dest: page === null ? null : [{ num: page }], page, children }
}

const FRISTEN = node('3.1 Fristen', 4, [node('3.1.1 Fristbeginn', 4)])
const OUTLINE = [node('1 Einleitung', 1), node('3 Verfahren', 3, [FRISTEN]), node('Anhang', null)]

describe('PdfOutline', () => {
  it('shows the children of top-level entries and folds deeper levels until expanded', async () => {
    renderWithProviders(<PdfOutline nodes={OUTLINE} activeNode={null} onSelect={vi.fn()} />)

    expect(screen.getByRole('button', { name: '3.1 Fristen' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '3.1.1 Fristbeginn' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '3.1 Fristen aufklappen' }))

    expect(screen.getByRole('button', { name: '3.1.1 Fristbeginn' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '3 Verfahren zuklappen' }))

    expect(screen.queryByRole('button', { name: '3.1 Fristen' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '3 Verfahren aufklappen' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
  })

  it('marks the active entry as the current location and jumps on click', async () => {
    const onSelect = vi.fn()
    renderWithProviders(<PdfOutline nodes={OUTLINE} activeNode={OUTLINE[0]} onSelect={onSelect} />)

    expect(screen.getByRole('button', { name: '1 Einleitung' })).toHaveAttribute(
      'aria-current',
      'location',
    )
    expect(screen.getByRole('button', { name: '3 Verfahren' })).not.toHaveAttribute('aria-current')

    await userEvent.click(screen.getByRole('button', { name: '3 Verfahren' }))

    expect(onSelect).toHaveBeenCalledWith(OUTLINE[1])
  })

  it('disables an entry without a destination', () => {
    renderWithProviders(<PdfOutline nodes={OUTLINE} activeNode={null} onSelect={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Anhang' })).toBeDisabled()
  })
})
