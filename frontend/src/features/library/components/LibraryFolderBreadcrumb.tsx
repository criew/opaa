import Breadcrumbs from '@mui/material/Breadcrumbs'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import type { LibraryFolderBreadcrumbItem } from '@/shared/api/types'

interface LibraryFolderBreadcrumbProps {
  breadcrumb: LibraryFolderBreadcrumbItem[]
  onOpenFolder: (folderId: string | null) => void
}

/** The Ordnerpfad from the root to the open folder; every entry but the last one is a link. */
export default function LibraryFolderBreadcrumb({
  breadcrumb,
  onOpenFolder: navigateToFolder,
}: LibraryFolderBreadcrumbProps) {
  return (
    <Breadcrumbs aria-label="Ordnerpfad" sx={{ mb: 2 }}>
      <Link component="button" underline="hover" onClick={() => navigateToFolder(null)}>
        Wurzel
      </Link>
      {breadcrumb.map((item, index) =>
        index === breadcrumb.length - 1 ? (
          <Typography key={item.id} sx={{ color: 'text.primary' }}>
            {item.name}
          </Typography>
        ) : (
          <Link
            key={item.id}
            component="button"
            underline="hover"
            onClick={() => navigateToFolder(item.id)}
          >
            {item.name}
          </Link>
        ),
      )}
    </Breadcrumbs>
  )
}
