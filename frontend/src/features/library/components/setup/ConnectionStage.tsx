import {
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useId,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import CheckIcon from '@mui/icons-material/Check'
import CloseIcon from '@mui/icons-material/Close'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { fontFamily } from '@/shared/theme/tokens'
import { SourceIssueContext, issueList } from '../sources/sourceIssueContext'
import { ConnectionGateContext } from './connectionGate'
import { STAGE_LABELS, type ConnectionOutcome, type ConnectionStageKey } from './connectionOutcome'

interface ConnectionStageProps {
  idPrefix: string
  /** Every value the test depends on; a result belongs to the token it was measured with. */
  token: string
  stages: ConnectionStageKey[]
  run: () => Promise<ConnectionOutcome>
  /** Address, account or way of connection, shown in the collapsed row. */
  summary: string[]
  /** The form values the fields edit; an issue naming one keeps the section open. */
  fields: string[]
  /** Runs the test on mount when the values are complete (edit mode, back in the wizard). */
  autoTest: boolean
  onOutcome?: (outcome: ConnectionOutcome | null) => void
  children?: ReactNode
}

interface Measured {
  token: string
  outcome: ConnectionOutcome
}

/**
 * The section „Verbindung" of every source form: the connection fields with one „Verbindung
 * testen". The result is inline and names the stage that failed (Adresse · Anmeldung · Rechte);
 * a passed test folds the section into one line with „Ändern", so the selection below gets the
 * room. A changed field voids the result, also one that arrives after the change.
 */
export default function ConnectionStage({
  idPrefix,
  token,
  stages,
  run,
  summary,
  fields,
  autoTest,
  onOutcome,
  children,
}: ConnectionStageProps) {
  const headingId = useId()
  const [measured, setMeasured] = useState<Measured | null>(null)
  const [testing, setTesting] = useState(false)
  const [editing, setEditing] = useState(false)
  const issues = issueList(useContext(SourceIssueContext))
  const gate = useContext(ConnectionGateContext)
  const editButton = useRef<HTMLButtonElement>(null)
  const firstField = useRef<HTMLDivElement>(null)

  const current = measured?.token === token ? measured.outcome : null
  const passed = current?.ok === true
  const fieldIssue = issues.some((issue) => issue.field && fields.includes(issue.field))
  const collapsed = passed && !editing && !fieldIssue

  const latest = useRef({ token, run, current })
  useLayoutEffect(() => {
    latest.current = { token, run, current }
  })

  const test = useCallback(async (): Promise<ConnectionOutcome> => {
    const measuredToken = latest.current.token
    setTesting(true)
    try {
      const outcome = await latest.current.run()
      setMeasured({ token: measuredToken, outcome })
      if (outcome.ok) setEditing(false)
      return outcome
    } finally {
      setTesting(false)
    }
  }, [])

  useEffect(() => {
    onOutcome?.(current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.ok, current?.message, token])

  useEffect(() => {
    if (!gate) return
    gate.register(async () => {
      if (latest.current.current?.ok) return true
      const outcome = await test()
      return outcome.ok && latest.current.token === token
    })
    return () => gate.register(null)
  }, [gate, test, token])

  // only the values at mount count: typing the last field must not start a test
  const autoAtMount = useRef(autoTest)
  useEffect(() => {
    if (autoAtMount.current) void test()
  }, [test])

  return (
    <Box component="section" aria-labelledby={headingId} data-source-field="connection">
      <Typography id={headingId} component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.5 }}>
        Verbindung
      </Typography>
      {collapsed ? (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            columnGap: 1.5,
            rowGap: 0.5,
            py: 1,
            borderTop: 1,
            borderBottom: 1,
            borderColor: 'divider',
          }}
        >
          <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }}>
            <CheckIcon aria-hidden sx={{ fontSize: 18, color: 'success.main' }} />
            <Typography sx={{ fontSize: 14, fontWeight: 500 }}>Verbunden</Typography>
          </Box>
          <Typography
            sx={{
              fontSize: 13,
              color: 'text.secondary',
              fontFamily: fontFamily.mono,
              minWidth: 0,
              flex: '1 1 12rem',
              overflowWrap: 'anywhere',
            }}
          >
            {summary.filter(Boolean).join(' · ')}
          </Typography>
          <Button
            ref={editButton}
            size="small"
            onClick={() => {
              setEditing(true)
              requestAnimationFrame(() => firstField.current?.querySelector('input')?.focus())
            }}
            aria-label="Verbindung ändern"
          >
            Ändern
          </Button>
        </Box>
      ) : (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Box ref={firstField} sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            {children}
          </Box>
          <Box>
            <BusyButton
              variant="outlined"
              onClick={() => void test()}
              busy={testing}
              busyAnnouncement="Verbindung wird getestet"
              id={`${idPrefix}-test`}
            >
              {testing ? 'Wird getestet …' : 'Verbindung testen'}
            </BusyButton>
          </Box>
          <Box role="status" aria-live="polite">
            {current && !current.ok && <StageResult stages={stages} outcome={current} />}
          </Box>
        </Box>
      )}
    </Box>
  )
}

/** The stages in order: passed ones ticked, the failed one crossed, later ones left out. */
function StageResult({
  stages,
  outcome,
}: {
  stages: ConnectionStageKey[]
  outcome: ConnectionOutcome
}) {
  const failedAt = outcome.failedStage ? stages.indexOf(outcome.failedStage) : -1
  return (
    <Box>
      <Box
        component="ol"
        aria-label="Prüfstufen"
        sx={{ display: 'flex', flexWrap: 'wrap', gap: 2, listStyle: 'none', m: 0, p: 0 }}
      >
        {stages.map((stage, index) => {
          const state =
            failedAt < 0 || index < failedAt ? 'ok' : index === failedAt ? 'failed' : 'open'
          if (state === 'open') return null
          return (
            <Box
              component="li"
              key={stage}
              sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, fontSize: 13 }}
            >
              {state === 'ok' ? (
                <CheckIcon aria-hidden sx={{ fontSize: 16, color: 'success.main' }} />
              ) : (
                <CloseIcon aria-hidden sx={{ fontSize: 16, color: 'error.main' }} />
              )}
              <span>
                {STAGE_LABELS[stage]}
                <Box component="span" sx={visuallyHiddenSx}>
                  {state === 'ok' ? ' in Ordnung' : ' fehlgeschlagen'}
                </Box>
              </span>
            </Box>
          )
        })}
      </Box>
      <Typography sx={{ fontSize: 13, mt: 0.5, color: 'text.primary' }}>
        {outcome.message}
      </Typography>
    </Box>
  )
}

const visuallyHiddenSx = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
} as const
