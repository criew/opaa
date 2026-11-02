import { useId } from 'react'
import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'
import DoneAllIcon from '@mui/icons-material/DoneAll'
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlined'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlineOutlined'
import RemoveCircleOutlineIcon from '@mui/icons-material/RemoveCircleOutlineOutlined'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { AnswerEvidence } from '@/shared/api/types'
import type { CitationIndex } from '@/shared/ui/markdown/citations'
import { describeEvidenceSummary } from '@/shared/ui/markdown/citations'
import { citationMarkSx } from '@/shared/ui/markdown/citationMark'
import { motion, radius } from '@/shared/theme/tokens'
import { describeAnswerEvidence } from './answerEvidenceText'

/** Footnote marks shown in the button before the rest folds into "+n". */
const VISIBLE_MARKS = 3
/** Diameter of a footnote mark in the button, in px. */
const MARK_SIZE = 20
/** How far each mark moves apart on hover, in px - the stack fans out towards the label. */
const FAN_OUT_PX = 3

const LEVEL_STYLE = {
  SUPPORTED: { Icon: CheckCircleOutlineIcon, color: 'success.main' },
  PARTIALLY_SUPPORTED: { Icon: ErrorOutlineIcon, color: 'warning.main' },
  UNSUPPORTED: { Icon: RemoveCircleOutlineIcon, color: 'text.secondary' },
} as const

interface EvidenceFooterProps {
  citations: CitationIndex
  onOpen: () => void
  /** The answer's Belegstand; absent for answers from before it was assessed. */
  evidence?: AnswerEvidence
  /** Side actions at the right end of the row - the answer's copy button. */
  actions?: ReactNode
}

/**
 * The foot of an answer: a hairline, then the button to the Belegfenster and the count line beside
 * it; side actions sit at the right end. With a Belegstand the button carries its symbol and level
 * word and the count line leads with the reason; without one it reads "Belege anzeigen" over the
 * answer's footnote numbers. The level is always a word, so colour never carries it alone.
 */
export default function EvidenceFooter({
  citations,
  onOpen,
  evidence,
  actions,
}: EvidenceFooterProps) {
  const summaryId = useId()
  const citationSummary = describeEvidenceSummary(citations)
  const assessed = evidence ? describeAnswerEvidence(evidence) : undefined
  if (!citationSummary && !assessed && !actions) return null
  const summary = [assessed?.reason, citationSummary].filter(Boolean).join(' · ')

  const numbers = citations.docs.flatMap((doc) => doc.numbers).sort((a, b) => a - b)
  const marks = evidence ? [] : numbers.slice(0, VISIBLE_MARKS).map(String)
  if (!evidence && numbers.length > VISIBLE_MARKS) {
    marks.push(`+${numbers.length - VISIBLE_MARKS}`)
  }
  const level = evidence ? LEVEL_STYLE[evidence.level] : undefined
  const levelIcon = level && <level.Icon aria-hidden sx={{ fontSize: 16, color: level.color }} />

  return (
    <Box sx={{ mt: 2 }}>
      <Box
        aria-hidden
        sx={(theme) => ({
          height: '1px',
          background: `linear-gradient(90deg, ${theme.palette.divider} 0%, ${theme.palette.divider} 55%, ${alpha(theme.palette.divider, 0)} 100%)`,
        })}
      />
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          flexWrap: 'wrap',
          columnGap: 1.5,
          rowGap: 0.75,
          mt: 1.25,
        }}
      >
        {assessed && !citationSummary && (
          <Typography
            component="span"
            data-testid="answer-evidence"
            data-level={evidence?.level}
            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, fontSize: 12.5 }}
          >
            {levelIcon}
            <Box component="span" sx={visuallyHidden}>
              {'Belegstand: '}
            </Box>
            <Box component="span" sx={{ fontWeight: 600 }}>
              {assessed.label}
            </Box>
            {assessed.reason && (
              <Box component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
                {' · '}
                {assessed.reason}
              </Box>
            )}
          </Typography>
        )}
        {citationSummary && (
          <>
            <ButtonBase
              aria-haspopup="dialog"
              aria-label={assessed ? `Belegstand: ${assessed.label} – Belege anzeigen` : undefined}
              data-testid={assessed ? 'answer-evidence' : undefined}
              data-level={evidence?.level}
              aria-describedby={summaryId}
              onClick={onOpen}
              sx={(theme) => {
                const accent = theme.palette.primary.main
                const transition = `transform ${motion.durationFastMs}ms ${motion.easeOut}`
                return {
                  gap: 1,
                  height: 30,
                  pl: marks.length > 0 ? 0.5 : 0.75,
                  pr: 1.25,
                  borderRadius: `${radius.pill}px`,
                  border: 1,
                  borderColor: 'divider',
                  color: 'text.primary',
                  transition: `background-color ${motion.durationFastMs}ms ${motion.easeOut}, border-color ${motion.durationFastMs}ms ${motion.easeOut}`,
                  '& .evidence-mark, & .evidence-arrow': { transition },
                  '&:hover': {
                    bgcolor: alpha(accent, theme.palette.mode === 'dark' ? 0.12 : 0.05),
                    borderColor: alpha(accent, 0.45),
                  },
                  '&:hover .evidence-arrow': { transform: 'translateX(2px)' },
                  ...Object.fromEntries(
                    marks.map((_, i) => [
                      `&:hover .evidence-mark:nth-of-type(${i + 1})`,
                      { transform: `translateX(${i * FAN_OUT_PX}px)` },
                    ]),
                  ),
                  '&:active': {
                    bgcolor: alpha(accent, theme.palette.mode === 'dark' ? 0.18 : 0.1),
                  },
                  '&.Mui-focusVisible': { borderColor: accent },
                  '@media (prefers-reduced-motion: reduce)': {
                    '&, & .evidence-mark, & .evidence-arrow': { transition: 'none' },
                    '&:hover .evidence-mark, &:hover .evidence-arrow': { transform: 'none' },
                  },
                }
              }}
            >
              <Box aria-hidden sx={{ display: 'flex', alignItems: 'center' }}>
                {levelIcon ??
                  (marks.length > 0 ? (
                    marks.map((mark, i) => (
                      <Box
                        key={mark}
                        component="span"
                        className="evidence-mark"
                        sx={(theme) => ({
                          ...citationMarkSx(theme, MARK_SIZE),
                          ml: i === 0 ? 0 : '-5px',
                          boxShadow: `0 0 0 2px ${theme.palette.background.default}`,
                          position: 'relative',
                          zIndex: marks.length - i,
                        })}
                      >
                        {mark}
                      </Box>
                    ))
                  ) : (
                    <DoneAllIcon sx={{ fontSize: 16, color: 'text.secondary' }} />
                  ))}
              </Box>
              <Typography component="span" sx={{ fontSize: 12.5, fontWeight: 600 }}>
                {assessed ? assessed.label : 'Belege anzeigen'}
              </Typography>
              <ArrowForwardIcon
                aria-hidden
                className="evidence-arrow"
                sx={{ fontSize: 14, color: 'primary.main' }}
              />
            </ButtonBase>
            <Typography
              id={summaryId}
              component="span"
              data-testid="evidence-summary"
              sx={{ fontSize: 11.5, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}
            >
              {summary}
            </Typography>
          </>
        )}
        {actions && <Box sx={{ ml: 'auto' }}>{actions}</Box>}
      </Box>
    </Box>
  )
}
