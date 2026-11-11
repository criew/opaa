import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import PlanConfirmation from './PlanConfirmation'

describe('PlanConfirmation', () => {
  it('confirms with the one primary button and changes with the secondary one', async () => {
    const onConfirm = vi.fn()
    const onChange = vi.fn()
    render(<PlanConfirmation agentName="Recherche" onConfirm={onConfirm} onChange={onChange} />)

    const confirm = screen.getByRole('button', { name: 'Bestätigen' })
    const change = screen.getByRole('button', { name: 'Ändern' })
    expect(confirm.className).toMatch(/contained/)
    expect(change.className).not.toMatch(/contained/)
    expect(screen.getByRole('group', { name: 'Plan von Recherche' })).toBeInTheDocument()

    await userEvent.click(change)
    await userEvent.click(confirm)
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('locks both while an answer is pending', () => {
    render(
      <PlanConfirmation agentName="Recherche" onConfirm={vi.fn()} onChange={vi.fn()} disabled />,
    )
    expect(screen.getByRole('button', { name: 'Bestätigen' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Ändern' })).toBeDisabled()
  })

  it('locks both buttons at the first click so a double click confirms once', async () => {
    const onConfirm = vi.fn()
    render(<PlanConfirmation agentName="Recherche" onConfirm={onConfirm} onChange={vi.fn()} />)

    await userEvent.dblClick(screen.getByRole('button', { name: 'Bestätigen' }))

    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Bestätigen' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Ändern' })).toBeDisabled()
  })
})
