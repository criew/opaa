import { useCallback, useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import type { SelfDisclosureStatus } from '@/shared/api/types'
import { apiErrorCode, apiErrorStatus } from '@/shared/api/apiErrorDetails'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import {
  consumeRecentLoginReturn,
  isRecentLoginRequired,
  recentLoginRefusalMessage,
} from '@/shared/session/recentLogin'
import { MY_DATA_ROUTE } from '@/shared/routes'
import RecentLoginDialog from '@/shared/ui/RecentLoginDialog'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import StatusLine, { type StatusTone } from '@/shared/ui/StatusLine'
import { createSelfDisclosureTicket, getSelfDisclosureStatus } from '../api/selfDisclosureApi'
import { submitSelfDisclosureDownload, type DownloadRefusal } from '../utils/selfDisclosureDownload'
import {
  ABORTED_MESSAGE,
  CAPACITY_MESSAGE,
  DISABLED_MESSAGE,
  LAST_ABORTED_MESSAGE,
  NOT_STARTED_MESSAGE,
  PERSONAL_ONLY_NOTICE,
  UNREACHABLE_MESSAGE,
  abortedText,
  blockedText,
  completedText,
  dailyLimitText,
  formatMoment,
} from '../utils/selfDisclosureMessages'

/** How often the page asks for the status while a download it started may still run. */
export const STATUS_POLL_MS = 4000
/** After this long without a recorded start the submitted ticket has lapsed unused. */
export const START_TIMEOUT_MS = 75_000

export const CONFIRM_PURPOSE =
  'Bevor Sie Ihre Daten herunterladen, bestätigen Sie bitte Ihre Anmeldung.'

type Phase = 'loading' | 'idle' | 'preparing' | 'confirming' | 'downloading'

interface Notice {
  text: string
  tone: StatusTone
}

/**
 * Whether `now` records an attempt that began after `before` was read. `sawRunning` covers an
 * attempt that broke off after midnight, when the counters of the new day may equal the old ones.
 */
function startedSince(
  before: SelfDisclosureStatus,
  now: SelfDisclosureStatus,
  sawRunning: boolean,
): boolean {
  return (
    sawRunning ||
    now.running ||
    now.startsToday !== before.startsToday ||
    now.lastCompletedAt !== before.lastCompletedAt ||
    now.lastResult !== before.lastResult
  )
}

function transportFailed(err: unknown): boolean {
  return apiErrorStatus(err) === null
}

/**
 * „Meine Daten“ in the person's own settings: what the self-disclosure contains, the hint against
 * compelled disclosure, and the download after a recent sign-in. The browser downloads the ZIP
 * itself (a native form submission), which tells the page neither that it completed nor that it
 * broke off. The page therefore reads the status after the submission - every few seconds while
 * the tab is visible and at once when the person returns to it - and reports the result of the
 * attempt from there; on the next visit the status alone still tells an aborted last attempt.
 */
export default function MyDataSection() {
  const productName = useBrandingStore((s) => s.branding.productName)
  const [status, setStatus] = useState<SelfDisclosureStatus | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [phase, setPhase] = useState<Phase>('loading')
  const [notice, setNotice] = useState<Notice | null>(null)
  const phaseRef = useRef<Phase>('loading')
  const baseline = useRef<SelfDisclosureStatus | null>(null)
  const submittedAt = useRef(0)
  const sawRunning = useRef(false)
  const polling = useRef(false)
  const stopListening = useRef<(() => void) | null>(null)

  const changePhase = useCallback((next: Phase) => {
    phaseRef.current = next
    setPhase(next)
  }, [])

  const load = useCallback(
    (): Promise<SelfDisclosureStatus | null> =>
      getSelfDisclosureStatus()
        .then((loaded) => {
          setStatus(loaded)
          setLoadError(null)
          return loaded
        })
        .catch((err: unknown) => {
          setLoadError(
            transportFailed(err)
              ? UNREACHABLE_MESSAGE
              : 'Der Stand Ihrer Selbstauskunft ließ sich nicht laden.',
          )
          return null
        }),
    [],
  )

  /**
   * Ends the watch. With `own` the status tells the outcome of this page's attempt; without it the
   * ticket was refused, and a start the status shows belongs to another tab.
   */
  const finish = useCallback(
    (now: SelfDisclosureStatus | null, fallback: Notice | null, own = true) => {
      stopListening.current?.()
      stopListening.current = null
      const before = baseline.current
      const seen = sawRunning.current
      baseline.current = null
      sawRunning.current = false
      changePhase('idle')
      if (own && now && before && startedSince(before, now, seen)) {
        const completed =
          now.lastResult === 'COMPLETED' && now.lastCompletedAt !== before.lastCompletedAt
        setNotice(
          completed
            ? { text: completedText(now), tone: 'success' }
            : { text: abortedText(now, ABORTED_MESSAGE), tone: 'error' },
        )
        return
      }
      setNotice(fallback)
    },
    [changePhase],
  )

  /** Reads the status once and settles the attempt when it has ended or never began. */
  const poll = useCallback(async () => {
    if (phaseRef.current !== 'downloading' || polling.current) return
    polling.current = true
    try {
      const now = await load()
      const before = baseline.current
      if (!now || !before || phaseRef.current !== 'downloading') return
      if (now.running) {
        sawRunning.current = true
        return
      }
      if (startedSince(before, now, sawRunning.current)) {
        finish(now, null)
      } else if (Date.now() - submittedAt.current > START_TIMEOUT_MS) {
        finish(now, { text: NOT_STARTED_MESSAGE, tone: 'error' })
      }
    } finally {
      polling.current = false
    }
  }, [finish, load])

  const handleRefusal = useCallback(
    async (refusal: DownloadRefusal) => {
      const now = await load()
      // a poll may have settled the attempt meanwhile
      if (phaseRef.current !== 'downloading') return
      const before = baseline.current
      // Only a 503 (or an unreadable answer) can follow a start: the attempt broke off before the
      // first byte. Any other refusal means the ticket was not redeemed, so a start in the status
      // belongs to another tab.
      const mayHaveStarted = refusal.status === null || refusal.status === 503
      if (mayHaveStarted && now && before && startedSince(before, now, sawRunning.current)) {
        if (!now.running) finish(now, null)
        return
      }
      const explainedByStatus = now !== null && (!now.enabled || now.blocked != null)
      finish(
        now,
        explainedByStatus
          ? null
          : {
              text: refusal.status === 503 ? CAPACITY_MESSAGE : NOT_STARTED_MESSAGE,
              tone: 'error',
            },
        false,
      )
    },
    [finish, load],
  )

  const start = useCallback(
    async (afterConfirmation: boolean, afterProviderSignIn = false) => {
      if (phaseRef.current === 'preparing' || phaseRef.current === 'downloading') return
      setNotice(null)
      changePhase('preparing')
      const before = await load()
      if (!before) {
        changePhase('idle')
        return
      }
      if (!before.enabled || before.blocked != null) {
        changePhase('idle')
        return
      }
      let ticket: string
      try {
        ticket = (await createSelfDisclosureTicket()).ticket
      } catch (err) {
        if (isRecentLoginRequired(err)) {
          if (afterConfirmation) {
            changePhase('idle')
            setNotice({ text: recentLoginRefusalMessage(afterProviderSignIn), tone: 'error' })
          } else {
            changePhase('confirming')
          }
          return
        }
        const now = transportFailed(err) ? null : await load()
        changePhase('idle')
        const explainedByStatus =
          now !== null &&
          (!now.enabled || now.blocked != null) &&
          (apiErrorStatus(err) === 429 || apiErrorCode(err) === 'SELF_DISCLOSURE_DISABLED')
        setNotice(
          explainedByStatus
            ? null
            : {
                text: transportFailed(err) ? UNREACHABLE_MESSAGE : NOT_STARTED_MESSAGE,
                tone: 'error',
              },
        )
        return
      }
      baseline.current = before
      sawRunning.current = false
      submittedAt.current = Date.now()
      stopListening.current?.()
      stopListening.current = submitSelfDisclosureDownload(ticket, (refusal) => {
        void handleRefusal(refusal)
      })
      changePhase('downloading')
    },
    [changePhase, handleRefusal, load],
  )

  useEffect(() => {
    // Under StrictMode the effect runs twice; the return is consumed by the first run only.
    const returned = consumeRecentLoginReturn()
    if (returned) {
      void Promise.resolve().then(() => start(true, true))
    } else {
      void load().then(() => {
        if (phaseRef.current === 'loading') changePhase('idle')
      })
    }
  }, [changePhase, load, start])

  useEffect(
    () => () => {
      stopListening.current?.()
    },
    [],
  )

  useEffect(() => {
    if (phase !== 'downloading') return
    const tick = () => {
      if (document.visibilityState !== 'hidden') void poll()
    }
    const onFocus = () => void poll()
    const timer = window.setInterval(tick, STATUS_POLL_MS)
    document.addEventListener('visibilitychange', tick)
    window.addEventListener('focus', onFocus)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', tick)
      window.removeEventListener('focus', onFocus)
    }
  }, [phase, poll])

  const busy = phase === 'preparing' || phase === 'downloading'
  const blocked = status ? blockedText(status) : null
  const disabled = status !== null && !status.enabled

  let stateLine: Notice | null = null
  if (phase === 'downloading') {
    stateLine = {
      text: status?.running
        ? 'Der Download läuft. Ihr Browser speichert die Datei; bei vielen Chats kann das einige Minuten dauern.'
        : 'Der Download wird gestartet …',
      tone: 'neutral',
    }
  } else if (notice) {
    stateLine = notice
  } else if (status && !disabled && status.lastResult === 'ABORTED' && !status.running) {
    stateLine = { text: abortedText(status, LAST_ABORTED_MESSAGE), tone: 'error' }
  }

  return (
    <Box sx={{ maxWidth: 640 }}>
      <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>
        Sie können die zu Ihnen in {productName} gespeicherten Daten herunterladen: Konto, Chats,
        Rechte, Gruppen, Spaces, Benachrichtigungen, Protokolleinträge über Sie und weitere. Sie
        erhalten eine ZIP-Datei mit einer lesbaren Übersicht und maschinenlesbaren Dateien.
      </Typography>
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
        Nicht enthalten sind Inhalte von Dokumenten und Originaldateien, Sicherungen, das technische
        Log des Betriebs und Inhalte anlassbezogener Klärungen über Sie; Auskunft darüber gibt die
        oder der Datenschutzbeauftragte. Die Selbstauskunft ersetzt keinen Auskunftsantrag: Sie
        können die Auskunft weiterhin bei Ihrer Behörde beantragen.
      </Typography>
      <Typography sx={{ fontSize: 13.5, fontWeight: 600, mb: 2.5 }}>
        {PERSONAL_ONLY_NOTICE}
      </Typography>

      {loadError && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => void load()}>
              Erneut laden
            </Button>
          }
        >
          {loadError}
        </Alert>
      )}

      {disabled ? (
        <Typography role="status" sx={{ fontSize: 13.5 }}>
          {DISABLED_MESSAGE}
        </Typography>
      ) : (
        <>
          {status && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1.5 }}>
              {dailyLimitText(status.dailyLimit)} Vor dem Download bestätigen Sie Ihre Anmeldung.
              {status.lastCompletedAt &&
                ` Zuletzt vollständig heruntergeladen: ${formatMoment(status.lastCompletedAt)}.`}
            </Typography>
          )}
          <BusyButton
            variant="contained"
            busy={busy}
            busyAnnouncement=""
            // unavailable, not disabled: a native disabled button drops the keyboard focus to the
            // page when a completed download uses up the day
            unavailable={phase === 'loading' || status === null || blocked !== null}
            onClick={() => void start(false)}
          >
            Daten herunterladen
          </BusyButton>
        </>
      )}

      {/* One polite region for every change of state: start, running, result, refusal. */}
      <Box role="status" aria-live="polite" sx={{ mt: 2 }}>
        {!disabled && blocked && phase !== 'downloading' && (
          <StatusLine headline={blocked} tone="warning" />
        )}
        {stateLine && <StatusLine headline={stateLine.text} tone={stateLine.tone} />}
      </Box>

      <RecentLoginDialog
        open={phase === 'confirming'}
        purpose={CONFIRM_PURPOSE}
        onConfirmed={() => {
          void start(true)
        }}
        onClose={() => changePhase('idle')}
        returnTo={MY_DATA_ROUTE}
      />
    </Box>
  )
}
