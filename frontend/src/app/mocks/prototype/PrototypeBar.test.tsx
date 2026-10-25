import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PrototypeBar from './PrototypeBar'

describe('PrototypeBar', () => {
  afterEach(() => window.sessionStorage.clear())

  it('navigates the app to the chosen entry', async () => {
    const navigate = vi.fn()
    render(<PrototypeBar navigate={navigate} />)

    await userEvent.selectOptions(screen.getByLabelText('Einstieg'), 'Dienstanweisungen')

    expect(navigate).toHaveBeenCalledWith('/libraries/library-dienstanweisungen')
  })

  it('shows the remembered scenario and hides the variant without registered variants', () => {
    window.sessionStorage.setItem('opaa.mockScenario', 'scale')
    render(<PrototypeBar navigate={vi.fn()} />)

    expect(screen.getByLabelText('Szenario')).toHaveValue('scale')
    expect(screen.queryByLabelText('Variante')).not.toBeInTheDocument()
  })

  it('collapses to its toggle and remembers that', async () => {
    render(<PrototypeBar navigate={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: /einklappen/ }))

    expect(screen.queryByLabelText('Szenario')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Prototyp/ })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    expect(window.sessionStorage.getItem('opaa.prototypeBar.collapsed')).toBe('true')
  })
})
