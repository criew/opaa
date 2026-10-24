import { useId, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import visuallyHidden from '@mui/utils/visuallyHidden'
import StateDot from '@/shared/ui/StateDot'
import type { LibraryDetailTab } from '../libraryDetailAccess'
import type { LibraryState, StateCause } from './libraryState'
import type { NextStep } from './nextSteps'

/** Where a link of the Steckbrief leads: a tab and, in the Reiter „Quelle“, a row. */
export type SteckbriefTarget = { tab: LibraryDetailTab; row?: string }

/** The search of the page with the target active. */
export type TargetSearch = (target: SteckbriefTarget) => string

export interface SteckbriefSource {
  connection: string | null
  scope: string | null
  /** Names of scope entries the last run could not read. */
  unreadable: string[]
  schedule: string | null
}

export interface SteckbriefUpload {
  storage: string | null
  lastUpload: string | null
  /** Rejected files of this session: „<Grund> (Datei: <Name>)“. */
  rejections: string[]
}

export interface LibrarySteckbriefProps {
  state: LibraryState
  /** The run's progress while it runs, from the same counts as its run row. */
  progress: number | null
  /** Polling of the running run gave up; the run may go on. */
  statusUnknown: boolean
  onRetryStatus: () => void
  /** Connector libraries; absent for an upload library. */
  source: SteckbriefSource | null
  upload: SteckbriefUpload | null
  documents: string
  /** Storage across the owner's private libraries. */
  privateStorage: string | null
  spaces: string | null
  responsible: string
  steps: NextStep[]
  onDismissSteps: () => void
  targetSearch: TargetSearch
}

const CAUSE_TARGET: Record<StateCause, SteckbriefTarget> = {
  connection: { tab: 'quelle', row: 'connection' },
  scope: { tab: 'quelle', row: 'scope' },
  schedule: { tab: 'quelle', row: 'schedule' },
  runs: { tab: 'quelle', row: 'runs' },
  documents: { tab: 'dokumente' },
  sharing: { tab: 'freigaben' },
}

function Block({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <Box component="section" aria-labelledby={id} sx={{ '& + &': { mt: 2.5 } }}>
      <Typography
        id={id}
        component="h2"
        sx={{ fontSize: 13, fontWeight: 600, color: 'text.primary', mb: 0.5 }}
      >
        {title}
      </Typography>
      {children}
    </Box>
  )
}

function Line({ children }: { children: ReactNode }) {
  return (
    <Typography
      component="div"
      sx={{ fontSize: 13, lineHeight: '22px', color: 'text.secondary', overflowWrap: 'anywhere' }}
    >
      {children}
    </Typography>
  )
}

/** A line that leads into a tab: the whole text is the link, in the colour of running text. */
function TargetLine({
  to,
  targetSearch,
  children,
  label,
}: {
  to: SteckbriefTarget
  targetSearch: TargetSearch
  children: ReactNode
  /** Names the place for assistive tech, e.g. „Verbindung“. */
  label?: string
}) {
  return (
    <Line>
      <Link
        component={RouterLink}
        to={{ search: targetSearch(to) }}
        replace
        underline="hover"
        color="inherit"
      >
        {label && (
          <Box component="span" sx={visuallyHidden}>
            {`${label}: `}
          </Box>
        )}
        {children}
      </Link>
    </Line>
  )
}

/** The state word as the link to its cause, with its one addition (D2). */
export function StateLine({
  state,
  targetSearch,
}: {
  state: LibraryState
  targetSearch: TargetSearch
}) {
  const word = <StateDot tone={state.tone} label={state.word} strong />
  return (
    <Box sx={{ display: 'flex', alignItems: 'baseline', flexWrap: 'wrap', columnGap: 0.75 }}>
      {state.cause ? (
        <Link
          component={RouterLink}
          to={{ search: targetSearch(CAUSE_TARGET[state.cause]) }}
          replace
          underline="hover"
          color="inherit"
          data-testid="library-state"
        >
          {word}
        </Link>
      ) : (
        <Box component="span" data-testid="library-state">
          {word}
        </Box>
      )}
      {state.detail && (
        <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
          · {state.detail}
        </Typography>
      )}
    </Box>
  )
}

function RejectionsDialog({
  rejections,
  open,
  onClose,
}: {
  rejections: string[]
  open: boolean
  onClose: () => void
}) {
  // „<Grund> (Datei: <Name>)“ grouped by reason: the reason once, its files below
  const groups = new Map<string, string[]>()
  for (const message of rejections) {
    const match = /^(.*) \(Datei: (.*)\)$/.exec(message)
    const reason = match ? match[1] : message
    const file = match ? match[2] : ''
    groups.set(reason, [...(groups.get(reason) ?? []), file].filter(Boolean))
  }
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Abgelehnte Dateien</DialogTitle>
      <DialogContent>
        {[...groups.entries()].map(([reason, files]) => (
          <Box key={reason} sx={{ '& + &': { mt: 2 } }}>
            <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>
              {reason} · {files.length}
            </Typography>
            <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
              {files.map((file) => (
                <Typography component="li" key={file} sx={{ fontSize: 13.5 }}>
                  {file}
                </Typography>
              ))}
            </Box>
          </Box>
        ))}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Schließen</Button>
      </DialogActions>
    </Dialog>
  )
}

/** The checklist after creating the library: ✓ done, ○ open, nothing else (no bar, no icons). */
function NextStepsBlock({
  steps,
  onDismiss,
  targetSearch,
}: {
  steps: NextStep[]
  onDismiss: () => void
  targetSearch: TargetSearch
}) {
  return (
    <Block title="Nächste Schritte">
      <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }} data-testid="next-steps">
        {steps.map((step) => (
          <Box
            component="li"
            key={step.key}
            sx={{ display: 'flex', gap: 1, fontSize: 13, lineHeight: '24px' }}
          >
            <Box
              component="span"
              aria-hidden
              sx={{ width: 14, flex: 'none', color: step.done ? 'success.main' : 'text.secondary' }}
            >
              {step.done ? '✓' : '○'}
            </Box>
            {step.done ? (
              <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
                {step.label}
                <Box component="span" sx={visuallyHidden}>
                  {' (erledigt)'}
                </Box>
              </Typography>
            ) : (
              <Link
                component={RouterLink}
                to={{ search: targetSearch({ tab: step.tab, row: step.row }) }}
                replace
                underline="hover"
                sx={{ fontSize: 13 }}
              >
                {step.label}
              </Link>
            )}
          </Box>
        ))}
      </Box>
      <Link
        component="button"
        type="button"
        underline="hover"
        onClick={onDismiss}
        sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}
      >
        Ausblenden
      </Link>
    </Block>
  )
}

/** The blocks of the Steckbrief, the same in the column and in the folded block below 1100 px. */
function SteckbriefBlocks(props: LibrarySteckbriefProps) {
  const { state, source, upload, targetSearch } = props
  const [rejectionsOpen, setRejectionsOpen] = useState(false)
  return (
    <>
      {props.steps.length > 0 && (
        <NextStepsBlock
          steps={props.steps}
          onDismiss={props.onDismissSteps}
          targetSearch={targetSearch}
        />
      )}
      <Block title="Zustand">
        <StateLine state={state} targetSearch={targetSearch} />
        {state.word === 'Läuft' && props.progress != null && !props.statusUnknown && (
          <LinearProgress
            variant="determinate"
            value={props.progress}
            aria-label="Fortschritt des Laufs"
            sx={{ mt: 1, height: 2, borderRadius: 999 }}
          />
        )}
        {props.statusUnknown && (
          <Line>
            Stand unbekannt ·{' '}
            <Link component="button" type="button" underline="hover" onClick={props.onRetryStatus}>
              Erneut abrufen
            </Link>
          </Line>
        )}
      </Block>
      {source && (
        <Block title="Quelle">
          {source.connection && (
            <TargetLine
              to={{ tab: 'quelle', row: 'connection' }}
              targetSearch={targetSearch}
              label="Verbindung"
            >
              {source.connection}
            </TargetLine>
          )}
          {source.scope && (
            <TargetLine
              to={{ tab: 'quelle', row: 'scope' }}
              targetSearch={targetSearch}
              label="Umfang"
            >
              {source.scope}
              {source.unreadable.length > 0 && (
                <Box component="span" sx={{ color: 'warning.main' }}>
                  {` · ${source.unreadable.length} nicht lesbar`}
                </Box>
              )}
            </TargetLine>
          )}
          {source.schedule && (
            <TargetLine
              to={{ tab: 'quelle', row: 'schedule' }}
              targetSearch={targetSearch}
              label="Zeitplan"
            >
              {source.schedule}
            </TargetLine>
          )}
        </Block>
      )}
      {upload && (
        <Block title="Hochladen">
          {upload.storage && <Line>{upload.storage}</Line>}
          {upload.rejections.length > 0 && (
            <Line>
              <Link
                component="button"
                type="button"
                underline="hover"
                onClick={() => setRejectionsOpen(true)}
                sx={{ fontSize: 13 }}
              >
                Abgelehnte Dateien anzeigen
              </Link>
            </Line>
          )}
          <RejectionsDialog
            rejections={upload.rejections}
            open={rejectionsOpen}
            onClose={() => setRejectionsOpen(false)}
          />
        </Block>
      )}
      <Block title="Bestand">
        <TargetLine to={{ tab: 'dokumente' }} targetSearch={targetSearch}>
          {props.documents}
        </TargetLine>
        {props.privateStorage && <Line>{props.privateStorage}</Line>}
        {props.spaces && (
          <TargetLine to={{ tab: 'zuordnungen' }} targetSearch={targetSearch}>
            {props.spaces}
          </TargetLine>
        )}
        <TargetLine to={{ tab: 'freigaben' }} targetSearch={targetSearch} label="Zuständig">
          {props.responsible}
        </TargetLine>
      </Block>
    </>
  )
}

/** Breakpoint of the column: below it, the Steckbrief folds into one line above the tabs. */
export const STECKBRIEF_MEDIA = '@media (min-width: 1100px)'
export const STECKBRIEF_WIDTH = 280

/**
 * The Steckbrief of a library: state, source in short, holdings and - after creating it - the next
 * steps. From 1100 px a column right of the tabs, the same on every tab; below, one line above the
 * tabs that unfolds into the same blocks.
 */
export default function LibrarySteckbrief(props: LibrarySteckbriefProps) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const openSteps = props.steps.filter((step) => !step.done).length
  // right after creating the library the folded Steckbrief opens once on its next steps
  const [unfoldedForSteps, setUnfoldedForSteps] = useState(false)
  if (openSteps > 0 && !unfoldedForSteps) {
    setUnfoldedForSteps(true)
    setOpen(true)
  }
  return (
    <>
      <Box
        component="aside"
        aria-label="Steckbrief"
        sx={{
          display: 'none',
          gridArea: 'side',
          [STECKBRIEF_MEDIA]: {
            display: 'block',
            position: 'sticky',
            top: 24,
            alignSelf: 'start',
            borderLeft: 1,
            borderColor: 'divider',
            pl: 3,
            pt: 1.25,
          },
        }}
      >
        <SteckbriefBlocks {...props} />
      </Box>
      <Box
        sx={{
          gridArea: 'toggle',
          [STECKBRIEF_MEDIA]: { display: 'none' },
          borderTop: 1,
          borderColor: 'divider',
          mb: 1,
        }}
      >
        <ButtonBase
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((value) => !value)}
          sx={{
            width: '100%',
            minHeight: 40,
            justifyContent: 'flex-start',
            gap: 1,
            textAlign: 'left',
            fontSize: 13,
            borderRadius: 1,
          }}
        >
          <Typography component="span" sx={{ fontSize: 13, fontWeight: 600, flex: 'none' }}>
            Steckbrief
          </Typography>
          <Box
            component="span"
            sx={{ minWidth: 0, flex: 1, overflow: 'hidden', whiteSpace: 'nowrap' }}
          >
            <StateDot tone={props.state.tone} label={props.state.word} />
            {props.state.detail && (
              <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
                {` · ${props.state.detail}`}
              </Typography>
            )}
            {openSteps > 0 && (
              <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
                {` · ${openSteps} ${openSteps === 1 ? 'Schritt' : 'Schritte'} offen`}
              </Typography>
            )}
          </Box>
          <ExpandMoreIcon
            aria-hidden
            sx={{
              fontSize: 20,
              color: 'text.secondary',
              transform: open ? 'rotate(180deg)' : 'none',
            }}
          />
        </ButtonBase>
        <Box id={panelId} hidden={!open} sx={{ pb: 2, pt: 1 }}>
          {open && <SteckbriefBlocks {...props} />}
        </Box>
      </Box>
    </>
  )
}
