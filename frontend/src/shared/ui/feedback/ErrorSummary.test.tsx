import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../../../test/test-utils'
import ErrorSummary from './ErrorSummary'

function Harness({ texts }: { texts: string[] }) {
  return (
    <>
      <ErrorSummary
        entries={texts.map((text, i) => ({
          text,
          onJump: () => document.getElementById(`field-${i}`)?.focus(),
        }))}
      />
      {texts.map((text, i) => (
        <input key={text} id={`field-${i}`} aria-label={text} />
      ))}
    </>
  )
}

describe('ErrorSummary', () => {
  it('names the count in its title and lists every issue as a link', () => {
    renderWithProviders(<Harness texts={['Verbindung: Adresse fehlt', 'Umfang: Spaces fehlen']} />)

    const summary = screen.getByRole('alert', { name: 'Fehler: 2 Angaben prüfen' })
    expect(summary).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Verbindung: Adresse fehlt' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Umfang: Spaces fehlen' })).toBeInTheDocument()
  })

  it('speaks of one issue in the singular', () => {
    renderWithProviders(<Harness texts={['Verbindung: API-Token fehlt']} />)

    expect(screen.getByRole('heading', { name: 'Fehler: 1 Angabe prüfen' })).toBeInTheDocument()
  })

  it('moves the focus to the field of a clicked issue', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness texts={['Verbindung: Adresse fehlt', 'Umfang: Spaces fehlen']} />)

    await user.click(screen.getByRole('button', { name: 'Umfang: Spaces fehlen' }))
    expect(screen.getByRole('textbox', { name: 'Umfang: Spaces fehlen' })).toHaveFocus()
  })

  it('renders nothing without issues', () => {
    renderWithProviders(<Harness texts={[]} />)

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
