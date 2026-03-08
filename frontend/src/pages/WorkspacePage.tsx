import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Divider from '@mui/material/Divider'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import UploadIcon from '@mui/icons-material/Upload'
import ManageAccountsIcon from '@mui/icons-material/ManageAccounts'
import { useNavigate, useParams } from 'react-router-dom'
import { useWorkspaceStore } from '../stores/workspaceStore'

function canUpload(role: string | undefined): boolean {
  return role === 'EDITOR' || role === 'ADMIN' || role === 'OWNER'
}

function canManage(role: string | undefined): boolean {
  return role === 'ADMIN' || role === 'OWNER'
}

export default function WorkspacePage() {
  const { workspaceId } = useParams()
  const navigate = useNavigate()
  const loadWorkspaces = useWorkspaceStore((s) => s.loadWorkspaces)
  const selectWorkspace = useWorkspaceStore((s) => s.selectWorkspace)
  const workspaces = useWorkspaceStore((s) => s.workspaces)
  const workspace = useWorkspaceStore((s) => s.selectedWorkspace)
  const documents = useWorkspaceStore((s) => s.selectedWorkspaceDocuments)
  const isLoadingDetails = useWorkspaceStore((s) => s.isLoadingDetails)
  const error = useWorkspaceStore((s) => s.error)

  useEffect(() => {
    if (workspaces.length === 0) {
      void loadWorkspaces()
    }
  }, [loadWorkspaces, workspaces.length])

  useEffect(() => {
    const effectiveWorkspaceId = workspaceId ?? workspaces[0]?.id
    if (effectiveWorkspaceId) {
      void selectWorkspace(effectiveWorkspaceId)
      if (!workspaceId) {
        navigate(`/workspaces/${effectiveWorkspaceId}`, { replace: true })
      }
    }
  }, [navigate, selectWorkspace, workspaceId, workspaces])

  if (isLoadingDetails && !workspace) {
    return (
      <Box sx={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress />
      </Box>
    )
  }

  if (!workspace) {
    return (
      <Box sx={{ flexGrow: 1, p: 3 }}>
        <Typography variant="h6">No workspace selected</Typography>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, overflowY: 'auto' }}>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      <Stack spacing={2.5}>
        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" spacing={2}>
            <Box>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
                <Typography variant="h5">{workspace.name}</Typography>
                <Chip label={workspace.type} size="small" color="primary" variant="outlined" />
              </Stack>
              <Typography color="text.secondary">
                {workspace.description || 'No description provided.'}
              </Typography>
            </Box>
            <Stack direction="row" spacing={1} alignItems="center">
              {canUpload(workspace.userRole) && (
                <Button variant="contained" startIcon={<UploadIcon />}>
                  Upload
                </Button>
              )}
              {canManage(workspace.userRole) && (
                <Button
                  variant="outlined"
                  startIcon={<ManageAccountsIcon />}
                  onClick={() => navigate(`/workspaces/${workspace.id}/manage`)}
                >
                  Manage Workspace
                </Button>
              )}
            </Stack>
          </Stack>
        </Paper>

        {workspace.type === 'PERSONAL' && (
          <Alert severity="info">This is your personal workspace.</Alert>
        )}

        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Typography variant="h6" gutterBottom>
            Documents
          </Typography>
          <Divider sx={{ mb: 2 }} />
          {documents.length === 0 ? (
            <Typography color="text.secondary">No documents in this workspace yet.</Typography>
          ) : (
            <Stack spacing={1.25}>
              {documents.map((document) => (
                <Box
                  key={document.id}
                  sx={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    gap: 2,
                    flexWrap: 'wrap',
                  }}
                >
                  <Typography>{document.name}</Typography>
                  <Typography color="text.secondary">
                    {document.type} · {document.ownerDisplayName} ·{' '}
                    {new Date(document.uploadedAt).toLocaleDateString()}
                  </Typography>
                </Box>
              ))}
            </Stack>
          )}
        </Paper>

        <Paper variant="outlined" sx={{ p: 2.5 }}>
          <Typography variant="h6" gutterBottom>
            Members
          </Typography>
          <Divider sx={{ mb: 2 }} />
          {workspace.members.length === 0 ? (
            <Typography color="text.secondary">No members found.</Typography>
          ) : (
            <Stack spacing={1}>
              {workspace.members.map((member) => (
                <Box key={member.userId} sx={{ display: 'flex', justifyContent: 'space-between' }}>
                  <Typography sx={{ fontFamily: 'monospace' }}>{member.userId}</Typography>
                  <Chip label={member.role} size="small" />
                </Box>
              ))}
            </Stack>
          )}
        </Paper>
      </Stack>
    </Box>
  )
}
