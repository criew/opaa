import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import AddIcon from '@mui/icons-material/Add'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import type { GroupListResponse } from '@/shared/api/types'
import { useGroupAdminListStore } from '../stores/groupAdminListStore'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import CreateGroupDialog from '../components/CreateGroupDialog'
import { PendingPlanHint } from '@/features/directory-sync'
import GroupEditDialog from '../components/GroupEditDialog'
import GroupFilterBar from '../components/GroupFilterBar'
import GroupList from '../components/GroupList'
import GroupMembersDialog from '../components/GroupMembersDialog'
import { PermissionTransferDialog } from '@/features/succession'
import { contentWidth } from '@/shared/theme/tokens'

/**
 * The group administration, built like the account list: head, primary action,
 * filter bar, table with row menu and the dialogs of the actions. The origin is shown on
 * every row, the effect as a summary; the member list loads only in the dialog
 * „Mitglieder" - its retrieval by the system administration is an audit event (ADR-0036,
 * Entscheidungen 2, 4 and 9).
 */
export default function GroupManagementPage() {
  const error = useGroupAdminListStore((s) => s.error)
  const loadGroups = useGroupAdminListStore((s) => s.loadGroups)
  const [createOpen, setCreateOpen] = useState(false)
  const [editing, setEditing] = useState<GroupListResponse | null>(null)
  const [members, setMembers] = useState<GroupListResponse | null>(null)
  const [transfer, setTransfer] = useState<GroupListResponse | null>(null)

  useEffect(() => {
    void loadGroups()
  }, [loadGroups])

  // Every act can move a group out of the current filter or change its counts, so the page
  // reloads when a dialog closes rather than patching one row.
  function closeAndReload(close: () => void) {
    close()
    void loadGroups()
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={GroupsOutlinedIcon}
          title="Gruppen"
          description="Alle Gruppen der Organisation. Interne Gruppen legen Sie hier an und pflegen sie. Gruppen eines Identitätsanbieters oder Verzeichnisses pflegt ihre Quelle – bei ihnen entscheiden Sie hier nur über den Schutz."
        />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {/* As with the accounts: on the left, flush with the table, a hint - here a waiting
           directory plan -, on the right the primary action. */}
        <Stack
          direction="row"
          spacing={2}
          sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}
        >
          <Box sx={{ minWidth: 0 }}>
            <PendingPlanHint />
          </Box>
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreateOpen(true)}>
            Gruppe anlegen
          </Button>
        </Stack>
        <GroupFilterBar />
        {/* After a loading error only the message is shown - an empty list below it would look like
           a filter without matches. */}
        {!error && (
          <GroupList
            onEdit={setEditing}
            onMembers={setMembers}
            onTransfer={setTransfer}
            onDeleted={() => void loadGroups()}
            onChanged={() => void loadGroups()}
          />
        )}

        <GroupEditDialog group={editing} onClose={() => closeAndReload(() => setEditing(null))} />
        <GroupMembersDialog
          group={members}
          onClose={() => closeAndReload(() => setMembers(null))}
        />
        {transfer && (
          <PermissionTransferDialog
            open
            onClose={() => setTransfer(null)}
            source={{ type: 'GROUP', id: transfer.id, name: transfer.name }}
            targetKinds={['GROUP']}
            scopes={['ASSET_GRANTS', 'SPACE_MEMBERSHIPS', 'CAPABILITIES', 'OWNERSHIP']}
            intro={`Die gewählten Rechte von „${transfer.name}“ gehen in einem Vorgang an die Zielgruppe. Mitgliedschaften der Gruppe werden dabei nicht verschoben.`}
            onTransferred={() => void loadGroups()}
          />
        )}
        <CreateGroupDialog
          open={createOpen}
          onClose={() => setCreateOpen(false)}
          onCreated={() => closeAndReload(() => setCreateOpen(false))}
        />
      </Box>
    </Box>
  )
}
