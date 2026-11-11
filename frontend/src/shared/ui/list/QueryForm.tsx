import type { FormEvent, ReactNode } from 'react'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import BusyButton from '../a11y/BusyButton'
import FormField from '../form/FormField'
import FormFieldRow from '../form/FormFieldRow'
import type { Sentence } from '../states/EmptyState'
import { contentWidth } from '../../theme/tokens'
import { periodProblem, REASON_HELP, REASON_LABEL } from './queryRules'

/**
 * A query form above a list (guidelines 5.3, „Abfrage vor der Liste"): one column of
 * `contentWidth.form`, fields from `FormField`, one primary button with a verb that is never
 * disabled - a missing value shows at its field after the click, not as a locked button.
 */
export default function QueryForm({
  label,
  submitLabel,
  busy,
  busyAnnouncement,
  onSubmit,
  children,
}: {
  /** Accessible name of the form. */
  label: string
  /** The verb, e.g. „Auskunft erstellen". */
  submitLabel: string
  busy: boolean
  busyAnnouncement: string
  onSubmit: () => void
  children: ReactNode
}) {
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!busy) onSubmit()
  }

  return (
    <Box
      component="form"
      noValidate
      aria-label={label}
      onSubmit={handleSubmit}
      sx={{ maxWidth: contentWidth.form, mb: 3 }}
    >
      <Stack spacing={2}>
        {children}
        <Box>
          <BusyButton
            type="submit"
            variant="contained"
            busy={busy}
            busyAnnouncement={busyAnnouncement}
          >
            {submitLabel}
          </BusyButton>
        </Box>
      </Stack>
    </Box>
  )
}

/** The period of a query as one pair of values side by side: „Von" and „Bis". */
export function QueryPeriod({
  from,
  to,
  onFromChange,
  onToChange,
  showProblems,
}: {
  from: string
  to: string
  onFromChange: (value: string) => void
  onToChange: (value: string) => void
  /** True once a submit was tried; until then an empty field is not yet an error. */
  showProblems: boolean
}) {
  const problem = showProblems ? periodProblem(from, to) : null
  return (
    <FormFieldRow>
      <FormField
        label="Von"
        type="datetime-local"
        value={from}
        onChange={(event) => onFromChange(event.target.value)}
        error={Boolean(problem?.from)}
        helperText={problem?.from}
        sx={{ flex: 1 }}
      />
      <FormField
        label="Bis"
        type="datetime-local"
        value={to}
        onChange={(event) => onToChange(event.target.value)}
        error={Boolean(problem?.to)}
        helperText={problem?.to}
        sx={{ flex: 1 }}
      />
    </FormFieldRow>
  )
}

/** The mandatory reason of a logged query, with the same label and help text everywhere. */
export function QueryReason({
  value,
  onChange,
  showProblems,
}: {
  value: string
  onChange: (value: string) => void
  showProblems: boolean
}) {
  const missing = showProblems && value.trim() === ''
  return (
    <FormField
      label={REASON_LABEL}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      error={missing}
      helperText={missing ? `Bitte einen Anlass angeben. ${REASON_HELP}` : REASON_HELP}
    />
  )
}

/**
 * What stands in place of the result before the first query: one sentence saying what will
 * appear, no empty frame.
 */
export function QueryPrompt({ message }: { message: Sentence }) {
  return <Typography sx={{ color: 'text.secondary' }}>{message}</Typography>
}
