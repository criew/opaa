import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import RemoveIcon from '@mui/icons-material/Remove'
import SubjectIcon from '@mui/icons-material/Subject'
import TocIcon from '@mui/icons-material/Toc'
import FitWidthIcon from '@mui/icons-material/WidthFull'
import { fontSize, radius, shadow } from '@/shared/theme/tokens'
import useFullScreenDialog from '@/shared/ui/dialog/useFullScreenDialog'

export interface PdfToolsProps {
  page: number
  numPages: number
  scale: number
  onPage: (page: number) => void
  onZoomIn: () => void
  onZoomOut: () => void
  onFitWidth: () => void
  /** Whether the PDF has an outline to toggle. */
  hasOutline: boolean
  outlineOpen: boolean
  outlineId: string
  onToggleOutline: () => void
  /** Whether a scan's recognized text can be toggled. */
  recognized: boolean
  recognizedOpen: boolean
  recognizedId: string
  onToggleRecognized: () => void
}

/**
 * The PDF view's tools as a floating pill over the pages (a level-2 layer, guidelines 4.3): the
 * outline, paging, zoom, page width and a scan's recognized text. On a phone it keeps paging and
 * zoom only; outline and recognized text are reached through the finding bar there.
 */
export default function PdfTools({
  page,
  numPages,
  scale,
  onPage,
  onZoomIn,
  onZoomOut,
  onFitWidth,
  hasOutline,
  outlineOpen,
  outlineId,
  onToggleOutline,
  recognized,
  recognizedOpen,
  recognizedId,
  onToggleRecognized,
}: PdfToolsProps) {
  const phone = useFullScreenDialog()
  return (
    <Box
      sx={{
        position: 'absolute',
        left: '50%',
        bottom: { xs: 12, sm: 20 },
        transform: 'translateX(-50%)',
        maxWidth: 'calc(100% - 24px)',
        zIndex: 5,
      }}
    >
      <Box
        role="toolbar"
        aria-label="Ansicht"
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
          bgcolor: 'background.paper',
          border: 1,
          borderColor: 'divider',
          borderRadius: `${radius.pill}px`,
          boxShadow: shadow.floating,
          px: 1,
          py: 0.5,
          whiteSpace: 'nowrap',
        }}
      >
        {!phone && hasOutline && (
          <>
            <Tooltip
              title={
                outlineOpen ? 'Inhaltsverzeichnis ausblenden' : 'Inhaltsverzeichnis einblenden'
              }
            >
              <IconButton
                size="small"
                aria-label="Inhaltsverzeichnis"
                aria-expanded={outlineOpen}
                aria-controls={outlineOpen ? outlineId : undefined}
                onClick={onToggleOutline}
              >
                <TocIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Divider orientation="vertical" flexItem sx={{ mx: 0.5, my: 0.5 }} />
          </>
        )}
        <Tooltip title="Vorherige Seite">
          <span>
            <IconButton
              size="small"
              aria-label="Vorherige Seite"
              disabled={page <= 1}
              onClick={() => onPage(page - 1)}
            >
              <ChevronLeftIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
        <Typography
          component="span"
          sx={{ fontSize: fontSize.text, minWidth: 88, textAlign: 'center' }}
        >
          {numPages > 0 ? `Seite ${page} von ${numPages}` : '…'}
        </Typography>
        <Tooltip title="Nächste Seite">
          <span>
            <IconButton
              size="small"
              aria-label="Nächste Seite"
              disabled={page >= numPages}
              onClick={() => onPage(page + 1)}
            >
              <ChevronRightIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
        <Divider orientation="vertical" flexItem sx={{ mx: 0.5, my: 0.5 }} />
        <Tooltip title="Verkleinern">
          <IconButton size="small" aria-label="Verkleinern" onClick={onZoomOut}>
            <RemoveIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Typography
          component="span"
          sx={{ fontSize: fontSize.meta, minWidth: 40, textAlign: 'center' }}
        >
          {Math.round(scale * 100)} %
        </Typography>
        <Tooltip title="Vergrößern">
          <IconButton size="small" aria-label="Vergrößern" onClick={onZoomIn}>
            <AddIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        {!phone && (
          <Tooltip title="Seitenbreite">
            <IconButton size="small" aria-label="Seitenbreite" onClick={onFitWidth}>
              <FitWidthIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
        {!phone && recognized && (
          <>
            <Divider orientation="vertical" flexItem sx={{ mx: 0.5, my: 0.5 }} />
            <Tooltip
              title={recognizedOpen ? 'Erkannten Text ausblenden' : 'Erkannten Text einblenden'}
            >
              <IconButton
                size="small"
                aria-label="Erkannter Text"
                aria-expanded={recognizedOpen}
                aria-controls={recognizedOpen ? recognizedId : undefined}
                onClick={onToggleRecognized}
              >
                <SubjectIcon fontSize="small" />
              </IconButton>
            </Tooltip>
          </>
        )}
      </Box>
    </Box>
  )
}
