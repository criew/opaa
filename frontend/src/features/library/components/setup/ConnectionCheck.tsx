import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import CheckIcon from '@mui/icons-material/Check'
import CloseIcon from '@mui/icons-material/Close'
import RemoveIcon from '@mui/icons-material/Remove'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import type { SourceConnectionTestRequest, SourceConnectionTestResponse } from '@/shared/api/types'
import { testLibrarySource } from '../../api/libraryApi'
import { useSetup, type CheckOutcome } from './setupContext'
import { STAGE_LABELS, checkResultOf, type CheckResult, type CheckStage } from './checkResult'

interface ConnectionCheckProps {
  /** The request of the current values; null while what it needs is missing. */
  request: () => SourceConnectionTestRequest | null | Promise<SourceConnectionTestRequest | null>
  /** Every value the result depends on; a different token hides the result. */
  token: string
  stages: CheckStage[]
  /** Called with each fresh response, e.g. to keep a verified flag in the values. */
  onResponse?: (response: SourceConnectionTestResponse | null) => void
  /** Shown instead of running when `request` returns null. */
  missingHint?: string
}

interface Outcome {
  token: string
  check: CheckResult
  accepted?: boolean
}

/**
 * The one connection check of every source form: the button „Verbindung testen“ in place, and
 * the same check run by „Weiter“ and „Speichern“ through the setup context. The result stands
 * inline, names each stage (Adresse · Anmeldung · Rechte) and lapses once a value changes.
 */
export default function ConnectionCheck({
  request,
  token,
  stages,
  onResponse,
  missingHint = 'Für den Test fehlen noch Angaben.',
}: ConnectionCheckProps) {
  const { registerCheck } = useSetup()
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [testing, setTesting] = useState(false)
  const [missing, setMissing] = useState<string | null>(null)
  const visible = outcome?.token === token ? outcome : null

  async function run(): Promise<CheckResult | null> {
    const mine = token
    setMissing(null)
    setTesting(true)
    let body: SourceConnectionTestRequest | null
    try {
      body = await request()
    } catch {
      body = null
    }
    if (!body) {
      setTesting(false)
      setMissing(mine)
      return null
    }
    try {
      const result = await testLibrarySource(body)
      const check = checkResultOf(stages, { result })
      setOutcome({ token: mine, check })
      onResponse?.(result)
      return check
    } catch (err) {
      const check = checkResultOf(stages, {
        error: err instanceof Error && err.message ? err.message : undefined,
      })
      setOutcome({ token: mine, check })
      onResponse?.(null)
      return check
    } finally {
      setTesting(false)
    }
  }

  // the latest closure, so the flow always checks the values that stand now
  const latest = useRef({ run, visible })
  useEffect(() => {
    latest.current = { run, visible }
  })
  useEffect(() => {
    if (!registerCheck) return
    registerCheck({
      ensure: async (): Promise<CheckOutcome> => {
        const { visible: shown, run: runNow } = latest.current
        if (shown?.check.passed || shown?.accepted) return 'passed'
        const check = await runNow()
        if (!check) return 'none'
        return check.passed ? 'passed' : 'failed'
      },
    })
    return () => registerCheck(null)
  }, [registerCheck])

  return (
    <Box>
      <BusyButton
        variant="outlined"
        busy={testing}
        busyAnnouncement="Verbindung wird getestet"
        onClick={() => void run()}
      >
        {testing ? 'Wird getestet …' : 'Verbindung testen'}
      </BusyButton>
      <Box role="status" aria-live="polite" sx={{ mt: visible || missing === token ? 1.5 : 0 }}>
        {missing === token && !visible && (
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{missingHint}</Typography>
        )}
        {visible && (
          <CheckResultView
            check={visible.check}
            stages={stages}
            accepted={Boolean(visible.accepted)}
            onAccept={() => setOutcome({ ...visible, accepted: true })}
          />
        )}
      </Box>
    </Box>
  )
}

function CheckResultView({
  check,
  stages,
  accepted,
  onAccept,
}: {
  check: CheckResult
  stages: CheckStage[]
  accepted: boolean
  onAccept: () => void
}) {
  return (
    <Box>
      <Box
        component="ul"
        aria-label="Ergebnis des Verbindungstests"
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 2,
          listStyle: 'none',
          m: 0,
          p: 0,
          fontSize: 13,
        }}
      >
        {stages.map((stage) => {
          const state = check.stages[stage] ?? 'skipped'
          const Icon = state === 'ok' ? CheckIcon : state === 'failed' ? CloseIcon : RemoveIcon
          return (
            <Box
              component="li"
              key={stage}
              sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}
            >
              <Icon
                aria-hidden
                sx={{
                  fontSize: 16,
                  color:
                    state === 'ok'
                      ? 'success.main'
                      : state === 'failed'
                        ? 'error.main'
                        : 'text.disabled',
                }}
              />
              <span>
                {STAGE_LABELS[stage]}
                <Box component="span" sx={visuallyHiddenSx}>
                  {state === 'ok'
                    ? ': in Ordnung'
                    : state === 'failed'
                      ? ': fehlgeschlagen'
                      : ': nicht geprüft'}
                </Box>
              </span>
            </Box>
          )
        })}
      </Box>
      <Typography sx={{ fontSize: 13, mt: 0.5, color: 'text.secondary', overflowWrap: 'anywhere' }}>
        {check.message}
      </Typography>
      {!check.passed && !accepted && (
        <Button size="small" variant="text" onClick={onAccept} sx={{ mt: 0.5, ml: -1 }}>
          Trotzdem fortfahren
        </Button>
      )}
      {!check.passed && accepted && (
        <Typography sx={{ fontSize: 13, mt: 0.5 }}>
          Sie fahren ohne erfolgreichen Test fort.
        </Typography>
      )}
    </Box>
  )
}

const visuallyHiddenSx = {
  border: 0,
  clip: 'rect(0 0 0 0)',
  height: '1px',
  margin: '-1px',
  overflow: 'hidden',
  padding: 0,
  position: 'absolute',
  whiteSpace: 'nowrap',
  width: '1px',
} as const
