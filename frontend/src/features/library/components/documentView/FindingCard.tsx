import { useId, useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import CenterFocusStrongIcon from '@mui/icons-material/CenterFocusStrong'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import { fontSize, fontWeight, letterSpacing, radius } from '@/shared/theme/tokens'

/** What the answer relied on, the same in every view: place, quote and the jump to it. */
export interface Finding {
  /** Page, row or section, as {@link ./findingText.anchorPlace} names it. */
  place: string | null
  /** The quote without markup; empty where the view shows the passage otherwise (a scan). */
  quote: string
}

/** The one status line of a view: what became of the passage. */
export interface FindingStatus {
  text: string
  /** A warning (passage not found, original changed) stands out in the warning role. */
  warning: boolean
}

/** A surface in the column beside the document: the card, the outline, the recognized text. */
export function FindingPanel({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        bgcolor: 'documentView.card',
        borderRadius: `${radius.md}px`,
        p: 2,
        display: 'flex',
        flexDirection: 'column',
        gap: 1.25,
        minWidth: 0,
      }}
    >
      {children}
    </Box>
  )
}

function Eyebrow({ children }: { children: ReactNode }) {
  return (
    <Typography
      component="h3"
      sx={{
        fontSize: fontSize.label,
        fontWeight: fontWeight.medium,
        letterSpacing: letterSpacing.caps,
        textTransform: 'uppercase',
        color: 'text.secondary',
        m: 0,
      }}
    >
      {children}
    </Typography>
  )
}

function Quote({ quote }: { quote: string }) {
  return (
    <Typography
      component="blockquote"
      sx={{
        m: 0,
        pl: 1.5,
        borderLeft: 3,
        borderColor: 'primary.main',
        fontSize: fontSize.text,
        color: 'text.secondary',
        overflowWrap: 'anywhere',
      }}
    >
      „{quote}“
    </Typography>
  )
}

function JumpButton({ onJump }: { onJump: () => void }) {
  return (
    <Button
      size="small"
      variant="contained"
      startIcon={<CenterFocusStrongIcon fontSize="small" />}
      onClick={onJump}
      sx={{ alignSelf: 'flex-start', flex: 'none', whiteSpace: 'nowrap' }}
    >
      Zur Fundstelle
    </Button>
  )
}

/** The status line; there is exactly one per view, wherever the layout puts it. */
export function StatusText({
  status,
  hidden = false,
}: {
  status: FindingStatus
  hidden?: boolean
}) {
  return (
    <Typography
      role="status"
      sx={
        hidden && !status.warning
          ? visuallyHidden
          : {
              fontSize: fontSize.text,
              color: status.warning ? 'warning.main' : 'text.secondary',
              fontWeight: status.warning ? fontWeight.semibold : fontWeight.regular,
            }
      }
    >
      {status.text}
    </Typography>
  )
}

/** The card beside the document: „Fundstelle“, place, quote, status and „Zur Fundstelle“. */
export function FindingCard({
  finding,
  status,
  onJump,
}: {
  finding: Finding
  status: FindingStatus
  onJump?: () => void
}) {
  return (
    <FindingPanel>
      <Eyebrow>Fundstelle</Eyebrow>
      {finding.place && (
        <Typography sx={{ fontSize: fontSize.text, fontWeight: fontWeight.semibold }}>
          {finding.place}
        </Typography>
      )}
      {finding.quote && <Quote quote={finding.quote} />}
      <StatusText status={status} />
      {onJump && <JumpButton onJump={onJump} />}
    </FindingPanel>
  )
}

/**
 * The card on a phone: one line that stays above the document („Seite 2 · Zur Fundstelle“). It
 * unfolds to the quote and the panels; a warning stays visible below the line.
 */
export function FindingBar({
  finding,
  status,
  onJump,
  panels,
}: {
  finding: Finding | null
  status: FindingStatus
  onJump?: () => void
  panels?: ReactNode
}) {
  const [open, setOpen] = useState(false)
  const detailsId = useId()
  const hasDetails = Boolean(finding?.quote) || Boolean(panels)
  const label = finding ? (finding.place ?? 'Fundstelle') : 'Inhalt'
  return (
    <Box
      component="section"
      aria-label={finding ? 'Fundstellen-Leiste' : 'Inhalt'}
      sx={{
        flex: 'none',
        bgcolor: 'documentView.card',
        borderBottom: 1,
        borderColor: 'divider',
        px: 1,
        py: 0.5,
        maxHeight: '50%',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
        {hasDetails ? (
          <Button
            size="small"
            color="inherit"
            aria-expanded={open}
            aria-controls={open ? detailsId : undefined}
            endIcon={open ? <ExpandLessIcon /> : <ExpandMoreIcon />}
            onClick={() => setOpen((value) => !value)}
            sx={{
              flex: 1,
              minWidth: 0,
              justifyContent: 'flex-start',
              '& .MuiButton-endIcon': { ml: 0.5 },
            }}
          >
            <Box
              component="span"
              sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
            >
              {label}
            </Box>
          </Button>
        ) : (
          <Typography
            sx={{
              flex: 1,
              minWidth: 0,
              px: 1,
              fontSize: fontSize.text,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {label}
          </Typography>
        )}
        {onJump && <JumpButton onJump={onJump} />}
      </Box>
      <Box sx={{ px: 1 }}>
        <StatusText status={status} hidden />
      </Box>
      {open && (
        <Box
          id={detailsId}
          sx={{
            overflow: 'auto',
            px: 1,
            pb: 1,
            pt: 0.5,
            display: 'flex',
            flexDirection: 'column',
            gap: 1.5,
          }}
        >
          {finding?.quote && <Quote quote={finding.quote} />}
          {panels}
        </Box>
      )}
    </Box>
  )
}
