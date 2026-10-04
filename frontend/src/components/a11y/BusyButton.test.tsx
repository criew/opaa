import { fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import BusyButton from './BusyButton'

function renderButton(props: { busy: boolean; disabled?: boolean; onClick?: () => void }) {
  return renderWithProviders(
    <BusyButton busyAnnouncement="Lädt" {...props}>
      Laden
    </BusyButton>,
  )
}

describe('BusyButton', () => {
  it('passes click, Enter and Space through while idle', async () => {
    const onClick = vi.fn()
    renderButton({ busy: false, onClick })
    const button = screen.getByRole('button', { name: 'Laden' })

    await userEvent.click(button)
    button.focus()
    await userEvent.keyboard('{Enter}')
    await userEvent.keyboard(' ')

    expect(onClick).toHaveBeenCalledTimes(3)
    expect(button).not.toHaveAttribute('aria-disabled')
  })

  it('swallows click, Enter and Space while busy but keeps focus and stays focusable', async () => {
    const onClick = vi.fn()
    renderButton({ busy: true, onClick })
    const button = screen.getByRole('button', { name: 'Laden' })

    await userEvent.click(button)
    expect(button).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    await userEvent.keyboard(' ')

    expect(onClick).not.toHaveBeenCalled()
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(button).toBeEnabled()
  })

  it('stays natively disabled when disabled and busy together', async () => {
    const onClick = vi.fn()
    renderButton({ busy: true, disabled: true, onClick })
    const button = screen.getByRole('button', { name: 'Laden' })

    fireEvent.click(button)

    expect(button).toBeDisabled()
    expect(onClick).not.toHaveBeenCalled()
  })

  it('announces the busy state in an always present status region', () => {
    const { rerender } = renderButton({ busy: false })
    const region = screen.getByRole('status')
    expect(region).toBeEmptyDOMElement()

    rerender(
      <BusyButton busyAnnouncement="Lädt" busy>
        Laden
      </BusyButton>,
    )

    expect(screen.getByRole('status')).toBe(region)
    expect(region).toHaveTextContent('Lädt')
  })

  it('does not submit a surrounding form while busy', async () => {
    const onSubmit = vi.fn((event: { preventDefault: () => void }) => event.preventDefault())
    const { rerender } = renderWithProviders(
      <form onSubmit={onSubmit}>
        <BusyButton type="submit" busyAnnouncement="Lädt" busy>
          Senden
        </BusyButton>
      </form>,
    )
    const button = screen.getByRole('button', { name: 'Senden' })

    await userEvent.click(button)
    button.focus()
    await userEvent.keyboard('{Enter}')
    expect(onSubmit).not.toHaveBeenCalled()

    rerender(
      <form onSubmit={onSubmit}>
        <BusyButton type="submit" busyAnnouncement="Lädt" busy={false}>
          Senden
        </BusyButton>
      </form>,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Senden' }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
  })
})
