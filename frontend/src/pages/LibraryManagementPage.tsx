import { useEffect } from 'react'
import { Link as RouterLink, useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import type { LibraryListResponse } from '../types/api'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import { useLibraryStore } from '../stores/libraryStore'
import { fontFamily } from '../theme/tokens'
import { assetRoleLabel, documentSourceTypeLabel, libraryVisibilityLabel } from '../utils/labels'
import GlobalBadge from '../components/GlobalBadge'
import PageHeading from '../components/a11y/PageHeading'
import MetaBadge from '../components/MetaBadge'

function ownerTypeSummary(library: LibraryListResponse): string {
  if (library.ownerName) return library.ownerName
  if (library.ownerType === 'GROUP') return 'Gruppen-Bibliothek'
  return 'eigene'
}

function documentCountSummary(documentCount: number): string {
  return `${documentCount.toLocaleString('de-DE')} ${documentCount === 1 ? 'Dokument' : 'Dokumente'}`
}

/** Mockup 1d's Stand wording (#684): "abgerufen" for the continuously fetched RSS feed,
 *  "indiziert" for everything else; today's runs show the local time instead of the date. */
function lastRunLabel(library: LibraryListResponse): string {
  if (!library.lastIndexedAt) return '–'
  const at = new Date(library.lastIndexedAt)
  const verb = library.sourceType === 'RSS_FEED' ? 'abgerufen' : 'indiziert'
  const isToday = at.toDateString() === new Date().toDateString()
  const when = isToday
    ? `heute ${at.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`
    : at.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' })
  return `${verb} ${when}`
}

/** The Stand cell: a live run with mockup 1d's mini progress bar, otherwise the last
 *  successful run from the list API (#684), or a dash before the first completed run. */
function StandCell({ library }: { library: LibraryListResponse }) {
  const run = useIndexingStore((s) => s.runsByLibrary[library.id]) ?? IDLE_RUN_STATE
  if (run.status !== 'RUNNING') {
    return <>{lastRunLabel(library)}</>
  }
  const percent =
    run.totalDocuments > 0 ? Math.round((run.documentCount / run.totalDocuments) * 100) : 0
  return (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.875 }}>
      <Box
        component="span"
        aria-hidden="true"
        sx={{
          width: 80,
          height: 4,
          bgcolor: 'divider',
          borderRadius: '2px',
          overflow: 'hidden',
          display: 'inline-block',
        }}
      >
        <Box
          component="span"
          sx={{
            display: 'block',
            width: `${percent}%`,
            height: '100%',
            bgcolor: 'primary.main',
          }}
        />
      </Box>
      Lauf läuft · {percent} %
    </Box>
  )
}

function LibraryTable({ libraries }: { libraries: LibraryListResponse[] }) {
  return (
    <>
      <Table
        size="small"
        sx={{
          '& th': { fontFamily: fontFamily.mono, fontSize: 10, letterSpacing: '0.08em' },
          '& td': { fontSize: 13.5, py: 1.375 },
        }}
      >
        <TableHead>
          <TableRow>
            <TableCell>Name</TableCell>
            <TableCell>Herkunft</TableCell>
            <TableCell>Umfang</TableCell>
            <TableCell>Verteilungsstufe</TableCell>
            <TableCell>Ihre Rolle</TableCell>
            <TableCell>Stand</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {libraries.map((library) => (
            <TableRow key={library.id} sx={{ position: 'relative' }}>
              <TableCell>
                {/* The stretched pseudo-element makes the whole row one click target while the
                    link itself stays the single tab stop (guidelines 5.3). */}
                <Typography
                  component={RouterLink}
                  to={`/libraries/${library.id}`}
                  sx={{
                    fontSize: 13.5,
                    fontWeight: 500,
                    color: 'text.primary',
                    textDecoration: 'none',
                    '&::after': { content: '""', position: 'absolute', inset: 0 },
                  }}
                >
                  {library.name}
                </Typography>
                <Typography component="div" sx={{ fontSize: 11.5, color: 'text.disabled' }}>
                  {[library.description, ownerTypeSummary(library)].filter(Boolean).join(' · ')}
                </Typography>
              </TableCell>
              <TableCell>{documentSourceTypeLabel(library.sourceType)}</TableCell>
              <TableCell sx={{ fontFamily: fontFamily.mono, fontSize: '12.5px !important' }}>
                {library.documentCount.toLocaleString('de-DE')} Dok.
              </TableCell>
              <TableCell>
                <Box component="span" sx={{ display: 'inline-flex', gap: 0.75 }}>
                  <MetaBadge>{libraryVisibilityLabel(library.visibility)}</MetaBadge>
                  {library.listed && <MetaBadge>gelistet</MetaBadge>}
                </Box>
              </TableCell>
              <TableCell>
                <MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>
              </TableCell>
              <TableCell sx={{ fontSize: '12px !important', color: 'text.secondary' }}>
                <StandCell library={library} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Typography sx={{ fontSize: 11.5, color: 'text.secondary', mt: 1.5 }}>
        Bestände ohne Leserecht erscheinen hier nicht. Laufende Vorgänge bleiben über einen
        Seitenwechsel hinweg sichtbar.
      </Typography>
    </>
  )
}

function LibraryListItem({ library }: { library: LibraryListResponse }) {
  return (
    // #506 review, finding 6: a real link (right-click "open in new tab", middle-click, browser
    // history) instead of a navigate()-triggering button - RouterLink gives ButtonBase an actual
    // href while keeping the button's focus/hover/ripple behaviour.
    <ButtonBase
      component={RouterLink}
      to={`/libraries/${library.id}`}
      sx={{
        display: 'block',
        width: '100%',
        textAlign: 'left',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        p: 1.5,
      }}
    >
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', width: '100%' }}>
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <Typography sx={{ fontWeight: 600 }}>{library.name}</Typography>
          <Typography variant="body2" color="text.secondary">
            {ownerTypeSummary(library)} · {libraryVisibilityLabel(library.visibility)}
            {library.listed ? ' · gelistet' : ''} · {documentCountSummary(library.documentCount)}
          </Typography>
        </Box>
        <Stack direction="row" spacing={0.5}>
          <Chip
            label={documentSourceTypeLabel(library.sourceType)}
            size="small"
            variant="outlined"
          />
          <Chip label={assetRoleLabel(library.myRole)} size="small" variant="outlined" />
        </Stack>
      </Stack>
    </ButtonBase>
  )
}

export default function LibraryManagementPage() {
  const navigate = useNavigate()
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const libraries = useLibraryStore((s) => s.libraries)
  const isLoading = useLibraryStore((s) => s.isLoading)
  const error = useLibraryStore((s) => s.error)
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)

  useEffect(() => {
    void loadLibraries()
  }, [loadLibraries])

  const bestaende = libraries.length === 1 ? '1 Bestand, den' : `${libraries.length} Bestände, die`

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, mb: 2.5, flexWrap: 'wrap' }}>
        <PageHeading title="Wissensbibliotheken" />
        <GlobalBadge />
        <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
          {bestaende} Sie lesen dürfen
        </Typography>
        <Button
          variant="contained"
          onClick={() => navigate('/libraries/new')}
          sx={{ ml: 'auto', flex: 'none' }}
        >
          Neue Bibliothek
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {isLoading ? (
        <Typography color="text.secondary">Bibliotheken werden geladen …</Typography>
      ) : libraries.length === 0 ? (
        <Typography color="text.secondary">Es sind noch keine Bibliotheken vorhanden.</Typography>
      ) : isDesktop ? (
        <LibraryTable libraries={libraries} />
      ) : (
        <Stack spacing={1}>
          {libraries.map((library) => (
            <LibraryListItem key={library.id} library={library} />
          ))}
        </Stack>
      )}
    </Box>
  )
}
