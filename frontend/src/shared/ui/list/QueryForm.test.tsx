import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { createAppTheme } from '../../theme/theme'
import QueryForm, { QueryPeriod, QueryReason } from './QueryForm'
import { periodProblem, REASON_HELP } from './queryRules'

const sources: Record<string, string> = import.meta.glob('/src/**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
})

function Harness({ onQuery }: { onQuery: () => void }) {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  return (
    <ThemeProvider theme={createAppTheme('light')}>
      <QueryForm
        label="Abfrage"
        submitLabel="Auskunft erstellen"
        busy={false}
        busyAnnouncement="Auskunft wird erstellt"
        onSubmit={() => {
          setAttempted(true)
          if (reason.trim() !== '' && !periodProblem(from, to)) onQuery()
        }}
      >
        <QueryPeriod
          from={from}
          to={to}
          onFromChange={setFrom}
          onToChange={setTo}
          showProblems={attempted}
        />
        <QueryReason value={reason} onChange={setReason} showProblems={attempted} />
      </QueryForm>
    </ThemeProvider>
  )
}

describe('QueryForm', () => {
  it('keeps the button usable and names the missing values at their fields', async () => {
    const onQuery = vi.fn()
    const user = userEvent.setup()
    render(<Harness onQuery={onQuery} />)

    const button = screen.getByRole('button', { name: 'Auskunft erstellen' })
    expect(button).toBeEnabled()
    expect(button).not.toHaveAttribute('aria-disabled', 'true')
    await user.click(button)

    expect(onQuery).not.toHaveBeenCalled()
    expect(screen.getByText('Bitte einen Beginn angeben.')).toBeInTheDocument()
    expect(screen.getByText('Bitte ein Ende angeben.')).toBeInTheDocument()
    expect(screen.getByLabelText('Anlass')).toHaveAccessibleDescription(
      `Bitte einen Anlass angeben. ${REASON_HELP}`,
    )
  })

  it('queries once every value is there', async () => {
    const onQuery = vi.fn()
    const user = userEvent.setup()
    render(<Harness onQuery={onQuery} />)

    await user.type(screen.getByLabelText('Von'), '2026-03-01T00:00')
    await user.type(screen.getByLabelText('Bis'), '2026-03-31T00:00')
    await user.type(screen.getByLabelText('Anlass'), 'Beschwerde 4711')
    await user.click(screen.getByRole('button', { name: 'Auskunft erstellen' }))

    expect(onQuery).toHaveBeenCalledOnce()
  })

  it('refuses a period that ends before it begins', () => {
    expect(periodProblem('2026-03-31T00:00', '2026-03-01T00:00')).toEqual({
      to: 'Das Ende muss nach dem Beginn liegen.',
    })
    expect(periodProblem('2026-03-01T00:00', '2026-03-31T00:00')).toBeNull()
  })

  it('gives every revision page its reason through QueryReason', () => {
    const offenders = Object.entries(sources)
      .filter(([path]) => path.startsWith('/src/features/audit/'))
      .filter(([path]) => !path.endsWith('.test.tsx'))
      .filter(([, source]) => /label=["{]['"]?Anlass\b|label="Von"|label="Bis"/.test(source))
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })
})
