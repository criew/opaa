import { Navigate, useLocation } from 'react-router'
import { useAuthStore } from '@/shared/session/authStore'
import { INSTALLATION_ROUTE } from '@/shared/routes'

/**
 * While the public configuration reports a pending installation (ADR-0060), the assistant is
 * the only page: every other route leads there. It reads the configuration the auth store loads
 * anyway and never asks the installation API itself - a request without token counts as a refused
 * token there. Unknown or unreadable, the installation counts as done.
 */
export default function InstallationRedirect() {
  const pending = useAuthStore((s) => s.installationPending)
  const { pathname } = useLocation()

  return pending && pathname !== INSTALLATION_ROUTE ? (
    <Navigate to={INSTALLATION_ROUTE} replace />
  ) : null
}
