import { useId, useRef, useState, type ReactNode, type Ref } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
// eslint-disable-next-line no-restricted-imports -- lab frame; the chosen one moves to shared/ui/dialog
import Dialog from '@mui/material/Dialog'
// eslint-disable-next-line no-restricted-imports -- lab frame; the chosen one moves to shared/ui/dialog
import Drawer from '@mui/material/Drawer'
import GlobalStyles from '@mui/material/GlobalStyles'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { useTheme, type SxProps, type Theme } from '@mui/material/styles'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import CloseIcon from '@mui/icons-material/Close'
import FullscreenIcon from '@mui/icons-material/Fullscreen'
import FullscreenExitIcon from '@mui/icons-material/FullscreenExit'
import ViewDialog from '@/shared/ui/dialog/ViewDialog'
import useFullScreenDialog from '@/shared/ui/dialog/useFullScreenDialog'
import { fontSize } from '@/shared/theme/tokens'
import { BRAND, deskColor, docViewVariant } from './docViewVariant'

export interface DocumentFrameProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  titleId: string
  titleActions?: ReactNode
  toolbar?: ReactNode
  contentSx?: SxProps<Theme>
  contentRef?: Ref<HTMLDivElement>
  /** A PDF takes the full height from the start; a text view grows with its content (Ist). */
  tall?: boolean
  children: ReactNode
}

const PANEL_MIN = 420
const PANEL_STEP = 48

/**
 * The frame around „Dokument öffnen“, per UI-lab variant: Ist is the 900 px view dialog; A a large
 * dialog with a full-screen toggle; B a panel beside the chat whose width is dragged; C a
 * full-screen reading mode (navy in the dark scheme).
 */
export default function DocumentFrame(props: DocumentFrameProps) {
  const variant = docViewVariant()
  if (variant === 'a') return <LargeDialog {...props} />
  if (variant === 'b') return <SidePanel {...props} />
  if (variant === 'c') return <ReadingMode {...props} />
  const { open, onClose, title, titleId, titleActions, toolbar, contentSx, contentRef } = props
  return (
    <ViewDialog
      open={open}
      onClose={onClose}
      width="editor"
      tall={props.tall}
      titleId={titleId}
      title={title}
      titleActions={titleActions}
      toolbar={toolbar}
      contentSx={contentSx}
      contentRef={contentRef}
    >
      {props.children}
    </ViewDialog>
  )
}

function Header({
  titleId,
  title,
  actions,
  onClose,
  inverse = false,
  back = false,
}: {
  titleId: string
  title: ReactNode
  actions?: ReactNode
  onClose: () => void
  inverse?: boolean
  back?: boolean
}) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        px: { xs: 1.5, sm: 2.5 },
        py: 1.25,
        borderBottom: inverse ? 0 : 1,
        borderColor: 'divider',
        color: inverse ? '#fff' : 'text.primary',
        '& .MuiIconButton-root': inverse ? { color: '#fff' } : undefined,
      }}
    >
      {back && (
        <Button
          onClick={onClose}
          startIcon={<ArrowBackIcon />}
          sx={{
            color: inverse ? '#fff' : 'primary.main',
            flex: 'none',
            mr: 1,
            display: { xs: 'none', sm: 'inline-flex' },
          }}
        >
          Zurück zur Antwort
        </Button>
      )}
      <Typography
        component="h2"
        id={titleId}
        noWrap
        sx={{ fontSize: fontSize.title, fontWeight: 600, flex: 1, minWidth: 0 }}
      >
        {title}
      </Typography>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, flex: 'none' }}>
        {actions}
        <Tooltip title="Schließen">
          <IconButton onClick={onClose} aria-label="Schließen">
            <CloseIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>
    </Box>
  )
}

function Content({ contentSx, contentRef, children }: DocumentFrameProps) {
  return (
    <Box
      ref={contentRef}
      sx={[
        { flex: 1, minHeight: 0, overflow: 'auto', display: 'flex', flexDirection: 'column' },
        ...(Array.isArray(contentSx) ? contentSx : [contentSx]),
      ]}
    >
      {children}
    </Box>
  )
}

/** A: 94 % of the viewport, up to 1480 px; one click makes it full screen. */
function LargeDialog(props: DocumentFrameProps) {
  const small = useFullScreenDialog()
  const [full, setFull] = useState(false)
  const fullScreen = small || full
  return (
    <Dialog
      open={props.open}
      onClose={props.onClose}
      maxWidth={false}
      fullScreen={fullScreen}
      aria-labelledby={props.titleId}
      slotProps={{
        paper: {
          sx: fullScreen
            ? { display: 'flex', flexDirection: 'column' }
            : {
                width: 'min(1480px, 94vw)',
                height: '94vh',
                maxHeight: 'none',
                m: 0,
                display: 'flex',
                flexDirection: 'column',
              },
        },
      }}
    >
      <Header
        titleId={props.titleId}
        title={props.title}
        onClose={props.onClose}
        actions={
          <>
            {props.titleActions}
            {!small && (
              <Tooltip title={full ? 'Vollbild verlassen' : 'Vollbild'}>
                <IconButton
                  aria-label={full ? 'Vollbild verlassen' : 'Vollbild'}
                  onClick={() => setFull((f) => !f)}
                >
                  {full ? (
                    <FullscreenExitIcon fontSize="small" />
                  ) : (
                    <FullscreenIcon fontSize="small" />
                  )}
                </IconButton>
              </Tooltip>
            )}
          </>
        }
      />
      {props.toolbar}
      <Content {...props} />
    </Dialog>
  )
}

/** B: a panel on the right, the answer stays visible beside it; its left edge is dragged. */
function SidePanel(props: DocumentFrameProps) {
  const small = useFullScreenDialog()
  const [width, setWidth] = useState(() =>
    typeof window === 'undefined' ? 900 : Math.round(Math.min(window.innerWidth * 0.6, 1200)),
  )
  const drag = useRef<number | null>(null)
  const handleId = useId()
  const max = typeof window === 'undefined' ? 1600 : Math.round(window.innerWidth * 0.92)
  const clampWidth = (w: number) => Math.min(max, Math.max(PANEL_MIN, w))
  return (
    <Drawer
      anchor="right"
      open={props.open}
      onClose={props.onClose}
      slotProps={{
        backdrop: { invisible: true },
        paper: {
          'aria-labelledby': props.titleId,
          role: 'dialog',
          sx: {
            width: small ? '100vw' : width,
            maxWidth: '100vw',
            display: 'flex',
            flexDirection: 'column',
            boxShadow: '-12px 0 32px rgba(36,40,85,0.18)',
          },
        } as object,
      }}
    >
      {!small && (
        <Box
          id={handleId}
          role="separator"
          aria-orientation="vertical"
          aria-label="Breite der Ansicht"
          aria-valuemin={PANEL_MIN}
          aria-valuemax={max}
          aria-valuenow={width}
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'ArrowLeft') setWidth((w) => clampWidth(w + PANEL_STEP))
            if (e.key === 'ArrowRight') setWidth((w) => clampWidth(w - PANEL_STEP))
          }}
          onPointerDown={(e) => {
            drag.current = e.clientX
            ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
          }}
          onPointerMove={(e) => {
            if (drag.current === null) return
            setWidth(clampWidth(window.innerWidth - e.clientX))
          }}
          onPointerUp={() => {
            drag.current = null
          }}
          sx={{
            position: 'absolute',
            left: 0,
            top: 0,
            bottom: 0,
            width: 12,
            ml: '-6px',
            cursor: 'col-resize',
            zIndex: 2,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            '&::after': {
              content: '""',
              width: 4,
              height: 48,
              borderRadius: 2,
              bgcolor: 'text.disabled',
            },
            '&:hover::after, &:focus-visible::after': { bgcolor: BRAND.blue, height: 72 },
            '&:focus-visible': { outline: `2px solid ${BRAND.blue}`, outlineOffset: -2 },
          }}
        />
      )}
      {/* the answer stays readable beside the panel: no backdrop dims or blurs it */}
      {props.open && (
        <GlobalStyles
          styles={{
            '.MuiBackdrop-root': {
              backdropFilter: 'none !important',
              background: 'transparent !important',
            },
          }}
        />
      )}
      <Header
        titleId={props.titleId}
        title={props.title}
        onClose={props.onClose}
        actions={props.titleActions}
      />
      {props.toolbar}
      <Content {...props} />
    </Drawer>
  )
}

/** C: full screen on a calm desk, navy in the dark scheme; the page is the brightest surface. */
function ReadingMode(props: DocumentFrameProps) {
  const dark = useTheme().palette.mode === 'dark'
  return (
    <Dialog
      open={props.open}
      onClose={props.onClose}
      fullScreen
      aria-labelledby={props.titleId}
      slotProps={{
        paper: {
          sx: (theme) => ({
            bgcolor: deskColor('c', theme.palette.mode === 'dark'),
            backgroundImage: 'none',
            display: 'flex',
            flexDirection: 'column',
          }),
        },
      }}
    >
      <Header
        titleId={props.titleId}
        title={props.title}
        onClose={props.onClose}
        actions={props.titleActions}
        inverse={dark}
        back
      />
      {props.toolbar}
      <Content {...props} />
    </Dialog>
  )
}
