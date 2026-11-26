import type { ReactNode, RefObject } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import FitWidthIcon from '@mui/icons-material/WidthFull'
import TocIcon from '@mui/icons-material/Toc'
import SubjectIcon from '@mui/icons-material/Subject'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import CenterFocusStrongIcon from '@mui/icons-material/CenterFocusStrong'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import type { ChunkAnchor } from '@/shared/api/types'
import { fontSize } from '@/shared/theme/tokens'
import { BRAND, deskColor, type DocViewVariant } from './docViewVariant'

type ViewProps = Omit<LabPdfLayoutProps, 'containerRef'>

export interface LabPdfLayoutProps {
  variant: Exclude<DocViewVariant, null>
  containerRef: RefObject<HTMLDivElement | null>
  loading: ReactNode
  page: number
  numPages: number
  scale: number
  onPage: (page: number) => void
  onZoom: (factor: number) => void
  onFit: () => void
  /** Scrolls back to the marked passage; absent while there is none. */
  onJump?: () => void
  outline: ReactNode | null
  outlineOpen: boolean
  onToggleOutline: () => void
  recognized: ReactNode | null
  recognizedOpen: boolean
  onToggleRecognized: () => void
  status: string
  warning: boolean
  anchor?: ChunkAnchor | null
  regionClass: string
}

/**
 * The PDF view of the lab variants: a page desk with paper shadows, one toolbar (A, B) or a
 * floating pill (C), the passage marks of the variant, and in C a card with the finding beside it.
 */
export default function LabPdfLayout({ containerRef, ...props }: LabPdfLayoutProps) {
  const { variant } = props
  const theme = useTheme()
  const dark = theme.palette.mode === 'dark'
  const desk = deskColor(variant, dark)
  const tools = <Tools {...props} pill={variant === 'c'} />
  const statusLine = props.status && variant !== 'c' && (
    <Box
      role="status"
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        px: 2.5,
        py: 0.75,
        fontSize: fontSize.text,
        color: props.warning ? 'warning.main' : 'text.secondary',
        fontWeight: props.warning ? 600 : 400,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      {props.warning && <WarningAmberIcon fontSize="small" />}
      {props.status}
    </Box>
  )
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
      {variant !== 'c' && tools}
      {statusLine}
      <Box
        sx={{ display: 'flex', flex: 1, minHeight: 0, flexDirection: { xs: 'column', sm: 'row' } }}
      >
        {variant === 'c' && <FindingCard {...props} />}
        {variant !== 'c' && props.outline && props.outlineOpen && (
          <Box
            id="pdf-outline"
            component="nav"
            aria-label="Inhaltsverzeichnis"
            sx={{
              width: { sm: 240, lg: 280 },
              maxHeight: { xs: '30%', sm: 'none' },
              flex: 'none',
              overflow: 'auto',
              borderRight: { xs: 'none', sm: '1px solid' },
              borderRightColor: 'divider',
              px: 1,
              py: 1.5,
              fontSize: fontSize.text,
            }}
          >
            {props.outline}
          </Box>
        )}
        <Box sx={{ position: 'relative', flex: 1, minWidth: 0, minHeight: 200, bgcolor: desk }}>
          {props.loading}
          <Box
            ref={containerRef}
            data-testid="pdf-container"
            tabIndex={0}
            role="region"
            aria-label="Seiten"
            sx={{
              position: 'absolute',
              inset: 0,
              overflow: 'auto',
              '--page-margin': '20px auto',
              '--page-border': 'none',
              '& .pdfViewer': { pb: variant === 'c' ? 10 : 2 },
              '& .pdfViewer .page': {
                boxShadow:
                  variant === 'c' && dark
                    ? '0 2px 6px rgba(0,0,0,0.25), 0 18px 48px rgba(0,0,0,0.35)'
                    : '0 1px 2px rgba(16,24,40,0.08), 0 10px 28px rgba(36,40,85,0.14)',
                borderRadius: '2px',
              },
              '& .textLayer .passage': { background: 'transparent' },
              [`& .${props.regionClass}`]: regionSx(variant),
              [`& .${props.regionClass}[data-first]::before`]:
                variant === 'c'
                  ? {
                      content: '"Fundstelle"',
                      position: 'absolute',
                      right: '100%',
                      top: -2,
                      mr: '6px',
                      bgcolor: BRAND.blue,
                      color: '#fff',
                      font: '600 11px/16px Quicksand, Inter, sans-serif',
                      px: 1,
                      py: '1px',
                      borderRadius: '4px',
                      whiteSpace: 'nowrap',
                    }
                  : undefined,
            }}
          >
            <div className="pdfViewer" />
          </Box>
          {variant === 'c' && (
            <Box
              sx={{
                position: 'absolute',
                left: '50%',
                bottom: 20,
                transform: 'translateX(-50%)',
                maxWidth: 'calc(100% - 24px)',
                zIndex: 5,
              }}
            >
              {tools}
            </Box>
          )}
        </Box>
        {variant !== 'c' && props.recognized && props.recognizedOpen && (
          <Box
            sx={{
              p: 2,
              borderLeft: { xs: 'none', sm: '1px solid' },
              borderTop: { xs: '1px solid', sm: 'none' },
              borderLeftColor: 'divider',
              borderTopColor: 'divider',
              display: 'flex',
              maxHeight: { xs: '35%', sm: 'none' },
              overflow: 'auto',
            }}
          >
            {props.recognized}
          </Box>
        )}
      </Box>
    </Box>
  )
}

function regionSx(variant: Exclude<DocViewVariant, null>) {
  const base = {
    position: 'absolute',
    zIndex: 3,
    pointerEvents: 'none',
    boxSizing: 'border-box',
  } as const
  if (variant === 'a')
    return {
      ...base,
      border: `2px solid ${BRAND.blue}`,
      borderRadius: '4px',
      background: BRAND.marker,
      mixBlendMode: 'multiply',
    }
  if (variant === 'b')
    return {
      ...base,
      background: BRAND.marker,
      borderBottom: `3px solid ${BRAND.blue}`,
      mixBlendMode: 'multiply',
    }
  return {
    ...base,
    border: `2px solid ${BRAND.blue}`,
    borderRadius: '6px',
    background: BRAND.markerSoft,
    mixBlendMode: 'multiply',
  }
}

function Tools(props: ViewProps & { pill: boolean }) {
  const { pill } = props
  const group = (children: ReactNode) => (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.25 }}>{children}</Box>
  )
  const sep = <Divider orientation="vertical" flexItem sx={{ mx: 0.75, my: 0.75 }} />
  return (
    <Box
      role="toolbar"
      aria-label="Ansicht"
      sx={{
        display: 'flex',
        alignItems: 'center',
        flexWrap: pill ? 'nowrap' : 'wrap',
        gap: 0.5,
        ...(pill
          ? {
              bgcolor: 'background.paper',
              borderRadius: 999,
              px: 1,
              py: 0.5,
              boxShadow: '0 6px 24px rgba(0,0,0,0.35)',
              overflowX: 'auto',
            }
          : { px: 2, py: 0.75, borderBottom: 1, borderColor: 'divider' }),
      }}
    >
      {props.outline &&
        group(
          <Tooltip
            title={
              props.outlineOpen ? 'Inhaltsverzeichnis ausblenden' : 'Inhaltsverzeichnis einblenden'
            }
          >
            <IconButton
              size="small"
              aria-label="Inhaltsverzeichnis"
              aria-expanded={props.outlineOpen}
              onClick={props.onToggleOutline}
            >
              <TocIcon fontSize="small" />
            </IconButton>
          </Tooltip>,
        )}
      {props.outline && sep}
      {group(
        <>
          <Tooltip title="Vorherige Seite">
            <span>
              <IconButton
                size="small"
                aria-label="Vorherige Seite"
                disabled={props.page <= 1}
                onClick={() => props.onPage(props.page - 1)}
              >
                <ChevronLeftIcon fontSize="small" />
              </IconButton>
            </span>
          </Tooltip>
          <Typography
            component="span"
            sx={{
              fontSize: fontSize.text,
              minWidth: 92,
              textAlign: 'center',
              whiteSpace: 'nowrap',
            }}
          >
            {props.numPages > 0 ? `Seite ${props.page} von ${props.numPages}` : 'Lädt …'}
          </Typography>
          <Tooltip title="Nächste Seite">
            <span>
              <IconButton
                size="small"
                aria-label="Nächste Seite"
                disabled={props.page >= props.numPages}
                onClick={() => props.onPage(props.page + 1)}
              >
                <ChevronRightIcon fontSize="small" />
              </IconButton>
            </span>
          </Tooltip>
        </>,
      )}
      {sep}
      {group(
        <>
          <Tooltip title="Verkleinern">
            <IconButton size="small" aria-label="Verkleinern" onClick={() => props.onZoom(0.8)}>
              <RemoveIcon fontSize="small" />
            </IconButton>
          </Tooltip>
          <Typography
            component="span"
            sx={{ fontSize: fontSize.meta, minWidth: 40, textAlign: 'center' }}
          >
            {Math.round(props.scale * 100)} %
          </Typography>
          <Tooltip title="Vergrößern">
            <IconButton size="small" aria-label="Vergrößern" onClick={() => props.onZoom(1.25)}>
              <AddIcon fontSize="small" />
            </IconButton>
          </Tooltip>
          <Tooltip title="Seitenbreite">
            <IconButton size="small" aria-label="Seitenbreite" onClick={props.onFit}>
              <FitWidthIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        </>,
      )}
      {!pill && <Box sx={{ flex: 1 }} />}
      {!pill && props.recognized && (
        <Tooltip
          title={props.recognizedOpen ? 'Erkannten Text ausblenden' : 'Erkannten Text einblenden'}
        >
          <IconButton
            size="small"
            aria-label="Erkannter Text"
            aria-expanded={props.recognizedOpen}
            onClick={props.onToggleRecognized}
          >
            <SubjectIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
      {props.onJump && (
        <>
          {pill && sep}
          <Button
            size="small"
            variant={pill ? 'text' : 'outlined'}
            startIcon={<CenterFocusStrongIcon fontSize="small" />}
            onClick={props.onJump}
            sx={{ whiteSpace: 'nowrap', flex: 'none' }}
          >
            Zur Fundstelle
          </Button>
        </>
      )}
    </Box>
  )
}

/** C: what the answer relied on, beside the original - quote, place, status, recognized text. */
function FindingCard(props: ViewProps) {
  const { anchor } = props
  const quote = [anchor?.quoteStart, anchor?.quoteEnd].filter(Boolean).join(' … ')
  const place = [
    anchor?.pageFrom != null
      ? anchor.pageTo != null && anchor.pageTo !== anchor.pageFrom
        ? `Seiten ${anchor.pageFrom}–${anchor.pageTo}`
        : `Seite ${anchor.pageFrom}`
      : null,
    anchor?.path?.at(-1),
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <Box
      component="aside"
      aria-label="Fundstelle"
      sx={{
        width: { sm: 300, lg: 340 },
        maxHeight: { xs: '40%', sm: 'none' },
        flex: 'none',
        overflow: 'auto',
        p: { xs: 1.5, sm: 2 },
        pt: { sm: 1 },
        display: 'flex',
        flexDirection: 'column',
        gap: 2,
      }}
    >
      <Box
        sx={{
          bgcolor: 'background.paper',
          borderRadius: 2,
          p: 2,
          display: 'flex',
          flexDirection: 'column',
          gap: 1.25,
          borderTop: `4px solid ${BRAND.light}`,
        }}
      >
        <Typography
          component="h3"
          sx={{
            fontSize: fontSize.meta,
            fontWeight: 700,
            letterSpacing: '0.06em',
            textTransform: 'uppercase',
            color: 'primary.main',
          }}
        >
          Fundstelle
        </Typography>
        {place && (
          <Typography sx={{ fontSize: fontSize.text, fontWeight: 600 }}>{place}</Typography>
        )}
        {quote && !props.recognized && (
          <Typography
            component="blockquote"
            sx={{
              display: { xs: 'none', sm: 'block' },
              m: 0,
              pl: 1.5,
              borderLeft: `3px solid ${BRAND.blue}`,
              fontSize: fontSize.text,
              color: 'text.secondary',
              fontStyle: 'italic',
            }}
          >
            „{quote}“
          </Typography>
        )}
        {props.status && (
          <Typography
            role="status"
            sx={{
              fontSize: fontSize.meta,
              color: props.warning ? 'warning.main' : 'text.secondary',
              fontWeight: props.warning ? 600 : 400,
            }}
          >
            {props.status}
          </Typography>
        )}
        {props.onJump && (
          <Button
            size="small"
            variant="contained"
            startIcon={<CenterFocusStrongIcon fontSize="small" />}
            onClick={props.onJump}
            sx={{ alignSelf: 'flex-start' }}
          >
            Zur Fundstelle
          </Button>
        )}
      </Box>
      {props.recognized && (
        <Box
          sx={{
            bgcolor: 'background.paper',
            borderRadius: 2,
            p: 2,
            display: { xs: 'none', sm: 'flex' },
          }}
        >
          {props.recognized}
        </Box>
      )}
      {props.outline && (
        <Box
          component="nav"
          aria-label="Inhaltsverzeichnis"
          sx={{
            display: { xs: 'none', sm: 'block' },
            bgcolor: 'background.paper',
            borderRadius: 2,
            p: 1,
            fontSize: fontSize.text,
          }}
        >
          <Typography
            component="h3"
            sx={{
              fontSize: fontSize.meta,
              fontWeight: 700,
              letterSpacing: '0.06em',
              textTransform: 'uppercase',
              color: 'text.secondary',
              px: 1,
              pt: 0.5,
            }}
          >
            Inhalt
          </Typography>
          {props.outline}
        </Box>
      )}
    </Box>
  )
}
