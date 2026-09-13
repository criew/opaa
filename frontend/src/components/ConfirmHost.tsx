import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import ErrorOutlineOutlinedIcon from '@mui/icons-material/ErrorOutlineOutlined'
import WarningAmberOutlinedIcon from '@mui/icons-material/WarningAmberOutlined'
import type { ConfirmTone } from '../stores/confirmStore'
import { useConfirmStore } from '../stores/confirmStore'

const TONE_COLOR: Record<ConfirmTone, string> = {
  danger: 'error.main',
  caution: 'warning.main',
  neutral: 'divider',
}

const TONE_ICON: Record<ConfirmTone, typeof ErrorOutlineOutlinedIcon | null> = {
  danger: ErrorOutlineOutlinedIcon,
  caution: WarningAmberOutlinedIcon,
  neutral: null,
}

/**
 * Das Bestätigungs-Overlay der Anwendung (#1610) — gerendert genau einmal, gespeist aus
 * {@link ../stores/confirmStore}. Es ersetzt `window.confirm`, das seine Frage in Systemschrift
 * stellte, die Folge im selben Textklumpen versteckte und mit „OK" bestätigen ließ.
 *
 * Drei Entscheidungen tragen die Form:
 *
 * - **Die Folge ist der Hauptinhalt.** Die Frage steht als Titel darüber, die Folge darunter in
 *   Lesebreite — nicht beides als eine Wand.
 * - **Die Schaltfläche trägt das Verb.** „Löschen", „Abschalten", „Verwerfen": Wer nur sie liest,
 *   weiß trotzdem, was er zusagt.
 * - **Bei einer unwiderruflichen Handlung liegt der Fokus auf „Abbrechen".** Ein Enter aus dem
 *   Reflex heraus bricht dann ab, statt zu löschen.
 *
 * Die Signalfarbe steht in der Kante und im Symbol, nie im Text: Als Fließtext misst
 * `warning.main` auf heller Fläche rund 1,8:1 und verfehlt 4,5:1 (Hausregel, siehe StatusLine).
 */
export default function ConfirmHost() {
  const request = useConfirmStore((s) => s.request)
  const settle = useConfirmStore((s) => s.settle)
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')

  const tone = request?.tone ?? 'neutral'
  const Icon = TONE_ICON[tone]

  return (
    <Dialog
      // Ein Schlüssel je Anfrage: Der Dialog fährt für eine zweite Frage neu auf, statt ihren
      // Text in den stehenden zu tauschen - und der Fokus wird dabei neu gesetzt.
      key={request?.id ?? 'none'}
      open={request !== null}
      onClose={() => settle(false)}
      maxWidth="xs"
      fullWidth
      transitionDuration={reducedMotion ? 0 : 160}
      aria-labelledby="confirm-question"
      aria-describedby={request?.consequence ? 'confirm-consequence' : undefined}
      slotProps={{
        paper: {
          sx: {
            borderLeft: 3,
            borderLeftColor: TONE_COLOR[tone],
          },
        },
      }}
    >
      <DialogTitle
        id="confirm-question"
        sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.25, fontSize: 17, pb: 1 }}
      >
        {Icon && (
          <Icon
            aria-hidden
            sx={{ fontSize: 20, color: TONE_COLOR[tone], flex: 'none', mt: '2px' }}
          />
        )}
        <Box component="span">{request?.question}</Box>
      </DialogTitle>
      {request?.consequence && (
        <DialogContent sx={{ pt: 0 }}>
          <Typography
            id="confirm-consequence"
            // `pre-line`: Eine Konsequenz aus zwei Sätzen unterschiedlicher Tragweite setzt sie
            // mit einer Leerzeile ab. Der automatische Umbruch bleibt davon unberührt.
            sx={{
              fontSize: 13.5,
              color: 'text.secondary',
              maxWidth: '60ch',
              whiteSpace: 'pre-line',
            }}
          >
            {request.consequence}
          </Typography>
        </DialogContent>
      )}
      <DialogActions sx={{ px: 3, pb: 2.5, pt: 2 }}>
        {/* Feste Kennungen: Die Beschriftung wechselt je Handlung, die E2E-Suite braucht aber
            einen Griff, der für jede Bestätigung derselbe ist. */}
        <Button
          id="confirm-cancel"
          onClick={() => settle(false)}
          // Der Startfokus eines modalen Dialogs ist erwünscht, anders als ein autoFocus auf
          // einer Seite: Bei einer unwiderruflichen Handlung liegt er hier bewusst auf dem
          // Abbruch, damit ein Enter aus dem Reflex heraus nichts zerstört.
          autoFocus={tone === 'danger'}
        >
          {request?.cancelLabel ?? 'Abbrechen'}
        </Button>
        <Button
          id="confirm-accept"
          variant="contained"
          color={tone === 'danger' ? 'error' : 'primary'}
          onClick={() => settle(true)}
          // eslint-disable-next-line jsx-a11y-x/no-autofocus
          autoFocus={tone !== 'danger'}
        >
          {request?.confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
