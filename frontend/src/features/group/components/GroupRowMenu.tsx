import { useState } from 'react'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import PauseCircleOutlineIcon from '@mui/icons-material/PauseCircleOutlined'
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutlined'
import PeopleOutlineIcon from '@mui/icons-material/PeopleOutlined'
import SwapHorizOutlinedIcon from '@mui/icons-material/SwapHorizOutlined'
import type { GroupListResponse } from '@/shared/api/types'
import RowMenu from '@/shared/ui/RowMenu'
import { setGroupDeactivation } from '../api/groupApi'
import { confirmGroupDeactivation } from './groupDeactivation'
import { confirmAction } from '@/shared/stores/confirmStore'
import { useGroupStore } from '../stores/groupStore'
import { notify } from '@/shared/stores/notificationStore'
import { GROUP_DELETE_CONSEQUENCE, PROVIDER_GROUP_DELETE_REASON } from './groupListLabels'

interface GroupRowMenuProps {
  group: GroupListResponse
  onEdit: (group: GroupListResponse) => void
  onMembers: (group: GroupListResponse) => void
  onTransfer: (group: GroupListResponse) => void
  /** After a deletion: the page reloads, because a row left it. */
  onDeleted: () => void
  /** After a deactivation or reactivation: the page reloads, because the state changed. */
  onChanged: () => void
}

/**
 * A group's row menu, built like an account's: Bearbeiten, Mitglieder and
 * „Rechte übertragen" open their dialog; „Deaktivieren" or „Reaktivieren" (internal
 * groups only) and „Löschen" (last, below a divider) present their quantified consequence
 * beforehand in the confirmation overlay. An entry that does not apply to this group is dimmed
 * and names the reason.
 */
export default function GroupRowMenu({
  group,
  onEdit,
  onMembers,
  onTransfer,
  onDeleted,
  onChanged,
}: GroupRowMenuProps) {
  const deleteExistingGroup = useGroupStore((s) => s.deleteExistingGroup)
  const [busy, setBusy] = useState(false)
  const deleteDisabled = group.kind !== 'AD_HOC'

  async function toggleDeactivation() {
    setBusy(true)
    try {
      const changed = await confirmGroupDeactivation(group, (deactivated) =>
        setGroupDeactivation(group.id, deactivated),
      )
      if (changed) onChanged()
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (
      !(await confirmAction({
        question: `„${group.name}“ löschen?`,
        consequence: GROUP_DELETE_CONSEQUENCE,
        confirmLabel: 'Löschen',
        tone: 'danger',
      }))
    ) {
      return
    }
    setBusy(true)
    try {
      await deleteExistingGroup(group.id)
      notify(`„${group.name}“ wurde gelöscht.`, 'success')
      onDeleted()
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Die Gruppe konnte nicht gelöscht werden.',
        'error',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <RowMenu
      name={group.name}
      disabled={busy}
      items={[
        { label: 'Bearbeiten', icon: EditOutlinedIcon, onClick: () => onEdit(group) },
        { label: 'Mitglieder', icon: PeopleOutlineIcon, onClick: () => onMembers(group) },
        {
          label: 'Rechte übertragen',
          icon: SwapHorizOutlinedIcon,
          onClick: () => onTransfer(group),
        },
        group.kind === 'AD_HOC' && {
          label: group.deactivated ? 'Reaktivieren' : 'Deaktivieren',
          icon: group.deactivated ? PlayCircleOutlineIcon : PauseCircleOutlineIcon,
          onClick: () => void toggleDeactivation(),
        },
        {
          label: 'Löschen',
          icon: DeleteOutlineIcon,
          onClick: () => void remove(),
          danger: true,
          disabled: deleteDisabled,
          disabledReason: PROVIDER_GROUP_DELETE_REASON,
        },
      ]}
    />
  )
}
