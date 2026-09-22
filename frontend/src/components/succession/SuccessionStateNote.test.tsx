import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AxiosError } from 'axios'
import { renderWithProviders } from '../../test/test-utils'
import SuccessionStateNote from './SuccessionStateNote'
import { successionAwareMessage } from './successionConflict'

describe('SuccessionStateNote', () => {
  // ADR-0036, Entscheidung 6 / Personalrat Z5: Zustand und Adressat - und sonst nichts.
  it('names state and addressee and says that nothing is deleted', () => {
    renderWithProviders(
      <SuccessionStateNote
        succession={{ addressee: 'SYSTEM_ADMINISTRATION', addresseeLabel: 'die Systemverwaltung' }}
      />,
    )

    expect(
      screen.getByText(/Nachfolge offen — zuständig: die Systemverwaltung/),
    ).toBeInTheDocument()
    expect(screen.getByText(/nichts wird gelöscht/)).toBeInTheDocument()
  })

  it('renders nothing while the object has a responsible party', () => {
    renderWithProviders(<SuccessionStateNote succession={null} />)

    expect(screen.queryByTestId('succession-note')).toBeNull()
  })
})

describe('successionAwareMessage', () => {
  function conflict(code: string, message: string): Error {
    const axiosError = new AxiosError('Request failed')
    axiosError.response = {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: { headers: {} } as never,
      data: { error: message, code },
    }
    return new Error(message, { cause: axiosError })
  }

  it('adds the way out to a refusal for an open succession', () => {
    const message = successionAwareMessage(
      conflict('SUCCESSION_OPEN', 'Für dieses Objekt ist die Nachfolge offen.'),
      'Fehlgeschlagen',
    )

    expect(message).toContain('Für dieses Objekt ist die Nachfolge offen.')
    expect(message).toContain('Übernahme')
  })

  it('leaves every other refusal untouched', () => {
    expect(successionAwareMessage(conflict('OTHER', 'Etwas anderes'), 'Fehlgeschlagen')).toBe(
      'Etwas anderes',
    )
  })
})
