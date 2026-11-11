import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { StatusTone } from '@/shared/ui/StatusLine'
import {
  controlHeight,
  darkRoles,
  fontFamily,
  fontSize,
  lightRoles,
  radius,
} from '@/shared/theme/tokens'
import type { InstallationStatusResponse } from '../api/types'
import {
  STEP_INFO,
  STEP_STATUS_LABEL,
  STEP_STATUS_TONE,
  SUMMARY_LABEL,
  TOKEN_LABEL,
  stepPosition,
  stepStatus,
  type WizardView,
} from '../utils/steps'

interface StepListProps {
  installation: InstallationStatusResponse | null
  view: WizardView
  /** Absent while no step may be opened, i.e. before the token was accepted. */
  onSelect?: (view: WizardView) => void
}

const DOT_COLOR: Record<StatusTone, string> = {
  neutral: 'text.disabled',
  success: 'success.main',
  warning: 'warning.main',
  error: 'error.main',
}

interface EntryProps {
  label: string
  status: string
  tone: StatusTone
  current: boolean
  onSelect?: () => void
}

/**
 * One entry: the step's name and its state as a word, the dot only marks it (guidelines 2.3).
 * The current entry carries `aria-current="step"`, weight and an accent edge - never colour alone.
 */
function Entry({ label, status, tone, current, onSelect }: EntryProps) {
  const theme = useTheme()
  // The column lies on bg-2, which has no palette slot; the raised entry is bg-1 (guidelines 2.3).
  const roles = theme.palette.mode === 'light' ? lightRoles : darkRoles
  const content = (
    <>
      <Box
        aria-hidden
        sx={{
          width: 8,
          height: 8,
          mt: '7px',
          borderRadius: '50%',
          flex: 'none',
          bgcolor: DOT_COLOR[tone],
        }}
      />
      <Box sx={{ minWidth: 0, textAlign: 'left' }}>
        <Typography
          component="span"
          sx={{
            display: 'block',
            fontSize: fontSize.text,
            fontWeight: current ? 600 : 500,
            color: 'text.primary',
          }}
        >
          {label}
        </Typography>
        <Typography component="span" sx={{ display: 'block', fontSize: fontSize.meta }}>
          {status}
        </Typography>
      </Box>
    </>
  )
  const sx = {
    display: 'flex',
    alignItems: 'flex-start',
    justifyContent: 'flex-start',
    gap: 1.5,
    width: '100%',
    py: 1,
    px: 1.5,
    borderRadius: `${radius.sm}px`,
    borderLeft: 3,
    borderColor: current ? 'primary.main' : 'transparent',
    bgcolor: current ? roles.bg1 : 'transparent',
    color: 'text.secondary',
  }
  return (
    <Box component="li" sx={{ listStyle: 'none' }}>
      {onSelect ? (
        <ButtonBase
          aria-current={current ? 'step' : undefined}
          onClick={onSelect}
          sx={{ ...sx, '&:hover': { bgcolor: roles.bg1 } }}
        >
          {content}
        </ButtonBase>
      ) : (
        <Box aria-current={current ? 'step' : undefined} sx={sx}>
          {content}
        </Box>
      )}
    </Box>
  )
}

function GroupTitle({ id, children }: { id: string; children: string }) {
  return (
    <Typography
      id={id}
      component="h2"
      sx={{
        fontFamily: fontFamily.mono,
        fontSize: fontSize.label,
        fontWeight: 500,
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        color: 'text.secondary',
        mt: 2.5,
        mb: 1,
        px: 1.5,
      }}
    >
      {children}
    </Typography>
  )
}

/**
 * The steps of the assistant, mandatory and optional ones in two labelled groups. Every step
 * can be opened from here once the token is accepted; its state comes from the API only. From
 * 900 px on the list stands open beside the step. Below that it shows one line with the position
 * and the current step's name and opens on a tap, so the step itself starts near the top; choosing
 * a step closes it again.
 */
export default function StepList({ installation, view, onSelect }: StepListProps) {
  const theme = useTheme()
  const narrow = useMediaQuery(theme.breakpoints.down('md'))
  const [open, setOpen] = useState(false)
  const listId = useId()
  const steps = installation?.steps ?? []
  const mandatory = steps.filter((s) => s.mandatory)
  const optional = steps.filter((s) => !s.mandatory)
  const select = (target: WizardView) =>
    onSelect
      ? () => {
          setOpen(false)
          onSelect(target)
        }
      : undefined

  const renderSteps = (list: typeof steps) =>
    list.map((s) => {
      const status = stepStatus(s)
      return (
        <Entry
          key={s.step}
          label={STEP_INFO[s.step].label}
          status={STEP_STATUS_LABEL[status]}
          tone={STEP_STATUS_TONE[status]}
          current={view === s.step}
          onSelect={select(s.step)}
        />
      )
    })

  return (
    <Box component="nav" aria-label="Schritte der Einrichtung">
      {narrow && (
        <ButtonBase
          aria-expanded={open}
          aria-controls={listId}
          onClick={() => setOpen((current) => !current)}
          sx={{
            display: 'flex',
            justifyContent: 'space-between',
            gap: 1,
            width: '100%',
            minHeight: controlHeight.regular,
            mt: 1,
            px: 1.5,
            borderRadius: `${radius.sm}px`,
            fontSize: fontSize.text,
            fontWeight: 500,
            textAlign: 'left',
          }}
        >
          {stepPosition(installation, view)}
          {open ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
        </ButtonBase>
      )}
      <Box id={listId} hidden={narrow && !open}>
        <GroupTitle id="installation-steps-required">Pflichtschritte</GroupTitle>
        <Box component="ol" aria-labelledby="installation-steps-required" sx={{ m: 0, p: 0 }}>
          <Entry
            label={TOKEN_LABEL}
            status={installation ? 'Geprüft' : 'Offen'}
            tone={installation ? 'success' : 'neutral'}
            current={view === 'TOKEN'}
          />
          {renderSteps(mandatory)}
        </Box>
        {optional.length > 0 && (
          <>
            <GroupTitle id="installation-steps-optional">Optionale Schritte</GroupTitle>
            <Box component="ol" aria-labelledby="installation-steps-optional" sx={{ m: 0, p: 0 }}>
              {renderSteps(optional)}
            </Box>
          </>
        )}
        {installation && (
          <Box component="ul" sx={{ m: 0, mt: 2.5, p: 0 }}>
            <Entry
              label={SUMMARY_LABEL}
              status={
                installation.completable
                  ? 'Bereit zum Abschluss'
                  : 'Erst nach allen Pflichtschritten'
              }
              tone={installation.completable ? 'success' : 'neutral'}
              current={view === 'SUMMARY'}
              onSelect={select('SUMMARY')}
            />
          </Box>
        )}
      </Box>
    </Box>
  )
}
