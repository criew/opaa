import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import CheckCircleOutlined from '@mui/icons-material/CheckCircleOutlined'
import ErrorOutlined from '@mui/icons-material/ErrorOutlined'
import RadioButtonUnchecked from '@mui/icons-material/RadioButtonUnchecked'
import WarningAmberOutlined from '@mui/icons-material/WarningAmberOutlined'
import type { CheckOutcome, CheckStage, StageState } from './types'

const WORDS: Record<CheckStage, Record<Exclude<StageState, 'none'>, string>> = {
  address: {
    ok: 'Adresse erreichbar',
    failed: 'Adresse nicht erreichbar',
    limited: 'Adresse erreichbar',
    open: 'Adresse nicht geprüft',
  },
  signIn: {
    ok: 'Anmeldung angenommen',
    failed: 'Anmeldung abgelehnt',
    limited: 'Anmeldung angenommen',
    open: 'Anmeldung nicht geprüft',
  },
  rights: {
    ok: 'Rechte ausreichend',
    failed: 'Rechte fehlen',
    limited: 'Rechte eingeschränkt',
    open: 'Rechte prüft der Schritt „Umfang“',
  },
}

const ORDER: CheckStage[] = ['address', 'signIn', 'rights']

function StageIcon({ state }: { state: StageState }) {
  const sx = { fontSize: 18, flex: 'none' }
  if (state === 'ok') return <CheckCircleOutlined aria-hidden sx={{ ...sx, color: 'success.main' }} />
  if (state === 'failed') return <ErrorOutlined aria-hidden sx={{ ...sx, color: 'error.main' }} />
  if (state === 'limited')
    return <WarningAmberOutlined aria-hidden sx={{ ...sx, color: 'warning.main' }} />
  return <RadioButtonUnchecked aria-hidden sx={{ ...sx, color: 'text.disabled' }} />
}

/**
 * The result of a connection check, the same on every source and page: one line per stage
 * (Adresse · Anmeldung · Rechte) with symbol and words, below it the connector's sentence.
 * `rightsOpenLabel` replaces the open rights stage where no step „Umfang“ follows.
 */
export default function CheckResult({
  outcome,
  rightsOpenLabel,
}: {
  outcome: CheckOutcome
  rightsOpenLabel?: string
}) {
  return (
    <Box data-testid="check-result" sx={{ mt: 1.5, maxWidth: 560 }}>
      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        {ORDER.filter((stage) => outcome.stages[stage] !== 'none').map((stage) => {
          const state = outcome.stages[stage] as Exclude<StageState, 'none'>
          const word =
            stage === 'rights' && state === 'open' && rightsOpenLabel ? rightsOpenLabel : WORDS[stage][state]
          return (
            <Box component="li" key={stage} sx={{ display: 'flex', alignItems: 'center', gap: 1, fontSize: 14 }}>
              <StageIcon state={state} />
              <span>{word}</span>
            </Box>
          )
        })}
      </Box>
      {outcome.message && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mt: 1, overflowWrap: 'anywhere' }}>
          {outcome.message}
        </Typography>
      )}
    </Box>
  )
}
