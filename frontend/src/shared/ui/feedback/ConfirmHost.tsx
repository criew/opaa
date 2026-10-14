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
import type { ConfirmTone } from '../../stores/confirmStore'
import { useConfirmStore } from '../../stores/confirmStore'

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
 * The application's confirmation overlay — rendered exactly once, fed from
 * {@link ../../stores/confirmStore}. It replaces `window.confirm`, which asked its question in the
 * system font, hid the consequence in the same lump of text and had it confirmed with „OK".
 *
 * Three decisions carry the form:
 *
 * - **The consequence is the main content.** The question is the title above, the consequence
 *   below it at reading width — not both as one wall.
 * - **The button carries the verb.** „Löschen", „Abschalten", „Verwerfen": whoever reads only it
 *   still knows what they agree to.
 * - **For an irreversible action focus is on „Abbrechen".** An Enter out of
 *   reflex then cancels instead of deleting.
 *
 * The signal colour is in the edge and the icon, never in the text (design guidelines 2.3).
 */
export default function ConfirmHost() {
  const request = useConfirmStore((s) => s.request)
  const settle = useConfirmStore((s) => s.settle)
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')

  const tone = request?.tone ?? 'neutral'
  const Icon = TONE_ICON[tone]

  return (
    <Dialog
      // One key per request: the dialog opens anew for a second question instead of swapping its
      // text into the standing one - and focus is set anew along the way.
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
            // `pre-line`: a consequence of two sentences of different weight sets them apart
            // with a blank line. Automatic wrapping is not affected.
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
        {/* Fixed identifiers: the label changes per action, but the E2E suite needs
           a handle that is the same for every confirmation. */}
        <Button
          id="confirm-cancel"
          onClick={() => settle(false)}
          // The initial focus of a modal dialog is desired, unlike an autoFocus on
          // a page: for an irreversible action it is deliberately on the cancel button here,
          // so that an Enter out of reflex destroys nothing.
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
