import { Navigate, useParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import ManageSearchOutlinedIcon from '@mui/icons-material/ManageSearchOutlined'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import AreaTabs from '@/shared/ui/AreaTabs'
import { useAuthStore } from '@/shared/session/authStore'
import { contentWidth } from '@/shared/theme/tokens'
import DiagnosisSection from '../components/DiagnosisSection'
import IndexStatusSection from '../components/IndexStatusSection'
import OverviewSection from '../components/OverviewSection'

export type SearchAdminTab = 'overview' | 'index' | 'diagnosis'

function isSearchAdminTab(value: string | undefined): value is SearchAdminTab {
  return value === 'overview' || value === 'index' || value === 'diagnosis'
}

// „Indexstatus" instead of „Bibliotheken": what is meant is not the content itself, which the
// catalog lists, but its state in the index.
const tabs: Array<{ value: SearchAdminTab; label: string }> = [
  { value: 'overview', label: 'Überblick' },
  { value: 'index', label: 'Indexstatus' },
  { value: 'diagnosis', label: 'Diagnose' },
]

/**
 * The system administration's search and indexing in three areas: „Überblick" answers
 * whether the search is running; „Indexstatus" shows the content per library and carries the two
 * batch runs; „Diagnose" answers why a document is in an answer or missing from it.
 *
 * The areas are routes of their own (`/admin/search/overview`, `/index`, `/diagnosis`) as on the
 * user and mail pages: a link should land in the right area, and a reload should
 * keep it. Every area loads only what it shows.
 */
export default function SearchIndexingAdminPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const { tab } = useParams()

  // A typo in the path is not silently reinterpreted: the address bar says what is shown.
  if (!isSearchAdminTab(tab)) {
    return <Navigate to="/admin/search/overview" replace />
  }
  const activeTab: SearchAdminTab = tab

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Suche & Indexierung" gutterBottom />
        <Alert severity="info">
          Suche und Indexierung werden von der Systemverwaltung betreut. Für Ihr Konto ist diese
          Seite nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={ManageSearchOutlinedIcon}
          title="Suche & Indexierung"
          description="Zeigt die aktive Konfiguration und warum ein Dokument in einer Antwort steht oder fehlt; eingreifen lässt sich nur über die Chargenläufe unter „Indexstatus“."
        />

        <AreaTabs
          tabs={tabs}
          value={activeTab}
          href={(value) => `/admin/search/${value}`}
          label="Bereiche von Suche und Indexierung"
          idPrefix="search"
        >
          {(value) => {
            if (value === 'overview') return <OverviewSection />
            if (value === 'index') return <IndexStatusSection />
            return <DiagnosisSection />
          }}
        </AreaTabs>
      </Box>
    </Box>
  )
}
