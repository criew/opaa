import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import CheckResult from './CheckResult'
import FieldList from './FieldList'
import type { CheckOutcome, FieldErrors, FieldSpec, StepContext, StepwiseSource } from './types'

interface ConnectionPageProps<V> {
  stepwise: StepwiseSource<V>
  specs: FieldSpec[]
  values: V
  onChange: (patch: Partial<V>) => void
  ctx: StepContext
  idPrefix: string
  errors: FieldErrors
  onLeave: (key: string) => void
  outcome: CheckOutcome | null
  checking: boolean
  onCheck: () => void
  /** Offered after a failed check: go on without a passed check. */
  onProceedAnyway?: () => void
  onReplacingChange: (replacing: boolean) => void
}

/**
 * The page „Verbindung“: the fields of the source, „Verbindung prüfen“ and the result in stages.
 * „Weiter“ (or „Speichern“) runs the same check; a result disappears once a field it was measured
 * with changes.
 */
export default function ConnectionPage<V>({
  stepwise,
  specs,
  values,
  onChange,
  ctx,
  idPrefix,
  errors,
  onLeave,
  outcome,
  checking,
  onCheck,
  onProceedAnyway,
  onReplacingChange,
}: ConnectionPageProps<V>) {
  return (
    <Box>
      {specs.length > 0 && (
        <FieldList
          specs={specs}
          values={values as Record<string, unknown>}
          onChange={(patch) => onChange(patch as Partial<V>)}
          errors={errors}
          idPrefix={idPrefix}
          mode={ctx.mode}
          credentialsStored={ctx.credentialsStored}
          replacing={ctx.replacingCredentials}
          onReplacingChange={onReplacingChange}
          onLeave={onLeave}
        />
      )}
      <Box sx={{ mt: specs.length > 0 ? 3 : 0 }}>
        <BusyButton
          variant="outlined"
          busy={checking}
          busyAnnouncement="Verbindung wird geprüft"
          onClick={onCheck}
        >
          {checking ? 'Wird geprüft …' : 'Verbindung prüfen'}
        </BusyButton>
        <Box role="status" aria-live="polite">
          {outcome && (
            <CheckResult
              outcome={outcome}
              rightsOpenLabel={stepwise.scope ? undefined : 'Rechte nicht geprüft'}
            />
          )}
        </Box>
        {outcome && !outcome.ok && onProceedAnyway && (
          <Button variant="text" size="small" onClick={onProceedAnyway} sx={{ mt: 1, ml: -1 }}>
            Ohne erfolgreiche Prüfung weiter
          </Button>
        )}
      </Box>
    </Box>
  )
}
