import CorporateFareOutlinedIcon from '@mui/icons-material/CorporateFareOutlined'
import ManageAccountsOutlinedIcon from '@mui/icons-material/ManageAccountsOutlined'
import type { AccountResponse } from '@/shared/api/types'
import RowMenu from '@/shared/ui/RowMenu'
import { providerLifecycleHint, roleManagedByProviderText } from './accountLabels'

interface ProviderAccountRowMenuProps {
  account: AccountResponse
  onChangeRole: (account: AccountResponse) => void
}

/**
 * The row menu of a provider account. It offers exactly what OPAA does on such an
 * account - the role (ADR-0033, Entscheidung 11: one role endpoint for both account types) -
 * and says what happens at the provider: the backend does not know locking, time-limiting and
 * deleting for provider accounts, and a menu must not pretend an action that does not exist.
 */
export default function ProviderAccountRowMenu({
  account,
  onChangeRole,
}: ProviderAccountRowMenuProps) {
  const name = account.displayName ?? account.email ?? account.id
  return (
    <RowMenu
      name={name}
      items={[
        {
          label: 'Rolle ändern …',
          icon: ManageAccountsOutlinedIcon,
          onClick: () => onChangeRole(account),
          disabled: account.roleManagedByProvider,
          disabledReason: roleManagedByProviderText(account),
        },
        {
          label: 'Anbieter verwalten',
          icon: CorporateFareOutlinedIcon,
          to: '/admin/identity-providers',
        },
      ]}
      note={providerLifecycleHint(account)}
    />
  )
}
