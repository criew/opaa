import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import AddIcon from '@mui/icons-material/Add'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import type { GroupListResponse } from '../types/api'
import { useGroupAdminListStore } from '../stores/groupAdminListStore'
import AreaPageHeader from '../components/AreaPageHeader'
import CreateGroupDialog from '../components/CreateGroupDialog'
import PendingPlanHint from '../components/admin/directorysync/PendingPlanHint'
import GroupEditDialog from '../components/admin/groups/GroupEditDialog'
import GroupFilterBar from '../components/admin/groups/GroupFilterBar'
import GroupList from '../components/admin/groups/GroupList'
import GroupMembersDialog from '../components/admin/groups/GroupMembersDialog'
import PermissionTransferDialog from '../components/permissions/PermissionTransferDialog'
import { contentWidth } from '../theme/tokens'

/**
 * Die Gruppenverwaltung (#1821, #1978), gebaut wie die Kontenliste: Kopf, primäre Handlung,
 * Filterleiste, Tabelle mit Zeilenmenü und die Dialoge der Handlungen. Die Herkunft steht an
 * jeder Zeile, die Wirkung als Zusammenfassung; die Mitgliederliste lädt erst im Dialog
 * „Mitglieder" - ihr Abruf durch die Systemverwaltung ist ein Audit-Ereignis (ADR-0036,
 * Entscheidungen 2, 4 und 9).
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
          description="Alle Gruppen der Organisation. Interne Gruppen legen Sie hier an und pflegen sie. Gruppen eines Identitätsanbieters oder Verzeichnisses pflegt ihre Quelle – bei ihnen legen Sie hier nur die Ansprechpersonen fest."
        />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {/* Wie bei den Konten: links, bündig mit der Tabelle, ein Hinweis - hier ein wartender
            Verzeichnisplan -, rechts die primäre Handlung. */}
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
        {/* Nach einem Ladefehler steht nur die Meldung da - eine leere Liste darunter sähe aus wie
            ein Filter ohne Treffer. */}
        {!error && (
          <GroupList
            onEdit={setEditing}
            onMembers={setMembers}
            onTransfer={setTransfer}
            onDeleted={() => void loadGroups()}
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
