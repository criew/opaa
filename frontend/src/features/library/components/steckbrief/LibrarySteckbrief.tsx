import { useId, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Skeleton from '@mui/material/Skeleton'
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
  /** Address of the source (host or path), the same short form for every type. */
  address: string | null
  scope: string | null
  /** Names of scope entries the last run could not read. */
  unreadable: string[]
  schedule: string | null
  nextRun: string | null
}

export interface SteckbriefUpload {
  storage: string | null
  lastUpload: string | null
  /** Rejected files of this session: „<Grund> (Datei: <Name>)“. */
  rejections: string[]
}

export interface LibrarySteckbriefProps {
  state: LibraryState
  /** The first status answer is still out: a placeholder instead of a word that may flip. */
  stateLoading?: boolean
  /** The run's progress while it runs, from the same counts as its run row. */
  progress: number | null
  /** Polling of the running run gave up; the run may go on. */
  statusUnknown: boolean
  onRetryStatus: () => void
  /** Connector libraries; absent for an upload library. */
  source: SteckbriefSource | null
  upload: SteckbriefUpload | null
  documents: string | null
  /** Storage across the owner's private libraries. */
  privateStorage: string | null
  spaces: string | null
  responsible: string
  steps: NextStep[]
  onDismissSteps: () => void
  targetSearch: TargetSearch
  /** The open tab; its own block (Quelle) is not repeated beside it (P50). */
  activeTab: LibraryDetailTab
  /** Whether the reader can reach the state's cause; a reader sees no connection or runs. */
  causeReachable: boolean
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

function Line({ children, title }: { children: ReactNode; title?: string }) {
  return (
    <Typography
      component="div"
      title={title}
      noWrap
      sx={{ fontSize: 13, lineHeight: '24px', color: 'text.secondary' }}
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
    <Line title={typeof children === 'string' ? children : undefined}>
      <Link
        component={RouterLink}
        to={{ search: targetSearch(to) }}
        replace
        underline="hover"
        sx={{
          display: 'inline-block',
          minHeight: 24,
          maxWidth: '100%',
          verticalAlign: 'top',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
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
  linkable = true,
}: {
  state: LibraryState
  targetSearch: TargetSearch
  linkable?: boolean
}) {
  const word = <StateDot tone={state.tone} label={state.word} strong />
  const detail = state.detail && (
    <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary', ml: 0.75 }}>
      · {state.detail}
    </Typography>
  )
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'baseline',
        flexWrap: 'wrap',
        columnGap: 0.75,
        minHeight: 24,
      }}
    >
      {state.cause && linkable ? (
        <Link
          component={RouterLink}
          to={{ search: targetSearch(CAUSE_TARGET[state.cause]) }}
          replace
          underline="hover"
          color="inherit"
          data-testid="library-state"
          sx={{ minHeight: 24, display: 'inline-flex', alignItems: 'center' }}
        >
          {word}
          {detail}
          {/* the arrow ends the line: word and addition lead to the cause together */}
          <Box component="span" aria-hidden sx={{ color: 'primary.main', ml: 0.5 }}>
            ›
          </Box>
        </Link>
      ) : (
        <Box component="span" data-testid="library-state">
          {word}
          {detail}
        </Box>
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
  openOnly = false,
}: {
  steps: NextStep[]
  onDismiss: () => void
  targetSearch: TargetSearch
  /** Only the open steps, for the narrow line above the tabs. */
  openOnly?: boolean
}) {
  const id = useId()
  const shown = openOnly ? steps.filter((step) => !step.done) : steps
  return (
    <Box component="section" aria-labelledby={id} sx={{ '& + &': { mt: 2.5 } }}>
      {/* „Ausblenden“ on the heading's line: the list ends with its last step */}
      <Box
        sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', mb: 0.5 }}
      >
        <Typography
          id={id}
          component="h2"
          sx={{ fontSize: 13, fontWeight: 600, color: 'text.primary' }}
        >
          Nächste Schritte
        </Typography>
        <Link
          component="button"
          type="button"
          underline="hover"
          onClick={onDismiss}
          sx={{ fontSize: 12.5, minHeight: 24 }}
        >
          Ausblenden
        </Link>
      </Box>
      <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }} data-testid="next-steps">
        {shown.map((step) => (
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
    </Box>
  )
}

/**
 * The blocks of the Steckbrief in a fixed order - Zustand, Quelle (or Hochladen), Bestand, then
 * the next steps - the same in the column and in the unfolded block below 1100 px.
 */
function SteckbriefBlocks({
  hideState = false,
  hideSteps = false,
  ...props
}: LibrarySteckbriefProps & { hideState?: boolean; hideSteps?: boolean }) {
  const { state, source, upload, targetSearch } = props
  const [rejectionsOpen, setRejectionsOpen] = useState(false)
  return (
    <>
      {!hideState && (
        <Block title="Zustand">
          {props.stateLoading ? (
            <Skeleton variant="text" width={160} height={24} />
          ) : (
            <StateLine state={state} targetSearch={targetSearch} linkable={props.causeReachable} />
          )}
          {props.statusUnknown && (
            <Line>
              Stand unbekannt ·{' '}
              <Link
                component="button"
                type="button"
                underline="hover"
                onClick={props.onRetryStatus}
              >
                Erneut abrufen
              </Link>
            </Line>
          )}
        </Block>
      )}
      {source && props.activeTab !== 'quelle' && (
        <Block title="Quelle">
          {source.address && (
            <TargetLine
              to={{ tab: 'quelle', row: 'connection' }}
              targetSearch={targetSearch}
              label="Verbindung"
            >
              {source.address}
            </TargetLine>
          )}
          {source.scope && (
            <TargetLine
              to={{ tab: 'quelle', row: 'scope' }}
              targetSearch={targetSearch}
              label="Umfang"
            >
              {source.scope}
            </TargetLine>
          )}
          {source.unreadable.length > 0 && (
            <Line>
              <StateDot tone="warning" label={`${source.unreadable.length} nicht lesbar`} />
            </Line>
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
          {source.nextRun && <Line>{source.nextRun}</Line>}
        </Block>
      )}
      {upload && (upload.storage || upload.rejections.length > 0) && (
        <Block title="Hochladen">
          {upload.storage && <Line>{upload.storage}</Line>}
          {upload.rejections.length > 0 && (
            <Line>
              <Link
                component="button"
                type="button"
                underline="hover"
                onClick={() => setRejectionsOpen(true)}
                sx={{ fontSize: 13, minHeight: 24 }}
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
        {props.documents && (
          <TargetLine to={{ tab: 'dokumente' }} targetSearch={targetSearch}>
            {props.documents}
          </TargetLine>
        )}
        {props.privateStorage && <Line>{props.privateStorage}</Line>}
        {props.spaces && (
          <TargetLine to={{ tab: 'zuordnungen' }} targetSearch={targetSearch}>
            {props.spaces}
          </TargetLine>
        )}
        <TargetLine to={{ tab: 'freigaben' }} targetSearch={targetSearch} label="Zuständig">
          {`${props.responsible} · zuständig`}
        </TargetLine>
      </Block>
      {props.steps.length > 0 && !hideSteps && (
        <NextStepsBlock
          steps={props.steps}
          onDismiss={props.onDismissSteps}
          targetSearch={targetSearch}
        />
      )}
    </>
  )
}

/** Breakpoint of the column: below it, the Steckbrief folds into one line above the tabs. */
export const STECKBRIEF_MEDIA = '@media (min-width: 1100px)'
export const STECKBRIEF_WIDTH = 280

/**
 * The Steckbrief of a library: state, source in short, holdings and - after creating it - the next
 * steps. From 1100 px a column right of the tabs; below, always one folded line above the tabs
 * (state word, open steps) that unfolds only on a click (pruefliste-detail D8, Richtung C).
 */
export default function LibrarySteckbrief(props: LibrarySteckbriefProps) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
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
            minWidth: 0,
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
            sx={{
              minWidth: 0,
              flex: 1,
              overflow: 'hidden',
              whiteSpace: 'nowrap',
              textOverflow: 'ellipsis',
            }}
          >
            {props.stateLoading ? (
              <Skeleton variant="text" width={120} sx={{ display: 'inline-block' }} />
            ) : (
              <StateDot tone={props.state.tone} label={props.state.word} />
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
        {/* right after creating the library its next steps stand open below the line */}
        {props.steps.length > 0 && (
          <Box sx={{ pb: 1.5 }}>
            <NextStepsBlock
              steps={props.steps}
              onDismiss={props.onDismissSteps}
              targetSearch={props.targetSearch}
              openOnly
            />
          </Box>
        )}
        <Box id={panelId} hidden={!open} sx={{ pb: 2, pt: 1 }}>
          {open && <SteckbriefBlocks {...props} hideState={!props.statusUnknown} hideSteps />}
        </Box>
      </Box>
    </>
  )
}
