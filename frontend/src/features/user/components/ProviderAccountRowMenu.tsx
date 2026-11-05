import type { AccountResponse } from '@/shared/api/types'
import RowMenu from '@/shared/ui/RowMenu'
import { providerLifecycleHint, roleManagedByProviderText } from './accountLabels'
import {
  ERASE_LABEL,
  ERASE_PROVIDER_ACTIVE_TOOLTIP,
  ERASE_SYSTEM_ROLE_TOOLTIP,
  useAccountErasure,
} from './useAccountErasure'

interface ProviderAccountRowMenuProps {
  account: AccountResponse
  onChangeRole: (account: AccountResponse) => void
}

/**
 * The row menu of a provider account. It offers exactly what OPAA does on such an
 * account - the role (ADR-0033, Entscheidung 11: one role endpoint for both account types) and the
 * removal of the personal reference - and says what happens at the provider: the backend does not
 * know locking and time-limiting for provider accounts, and a menu must not pretend an action that
 * does not exist. Such an account counts as deactivated only while the directory locks it or its
 * provider is gone; the backend refuses any other with 409.
 */
export default function ProviderAccountRowMenu({
  account,
  onChangeRole,
}: ProviderAccountRowMenuProps) {
  const name = account.displayName ?? account.email ?? account.id
  const erasure = useAccountErasure()
  const roleHeld = account.systemRole !== 'USER'
  const deactivated = account.directoryLocked === true || !account.provider
  const eraseDisabledReason = roleHeld
    ? ERASE_SYSTEM_ROLE_TOOLTIP
    : !deactivated
      ? ERASE_PROVIDER_ACTIVE_TOOLTIP
      : null
  return (
    <>
      <RowMenu
        name={name}
        disabled={erasure.busy}
        items={[
          {
            label: 'Rolle ändern …',
            onClick: () => onChangeRole(account),
            disabled: account.roleManagedByProvider,
            disabledReason: roleManagedByProviderText(account),
          },
          {
            label: 'Anbieter verwalten',
            to: '/admin/identity-providers',
          },
          {
            label: ERASE_LABEL,
            onClick: () => void erasure.erase(account.id, name),
            danger: true,
            disabled: eraseDisabledReason !== null,
            disabledReason: eraseDisabledReason ?? undefined,
          },
        ]}
        note={providerLifecycleHint(account)}
      />
      {erasure.dialog}
    </>
  )
}
