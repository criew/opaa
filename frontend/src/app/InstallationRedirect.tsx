import { useEffect, useState } from 'react'
import { Navigate, useLocation } from 'react-router'
import { probeInstallationPending } from '@/features/installation'
import { INSTALLATION_ROUTE } from '@/shared/routes'

/**
 * While the installation is pending, the assistant is the only page (ADR-0060): every other
 * route leads there. Asked once per page load beside the routes, so an installed instance - the
 * regular case - waits for nothing.
 */
export default function InstallationRedirect() {
  const [pending, setPending] = useState(false)
  const { pathname } = useLocation()

  useEffect(() => {
    let active = true
    void probeInstallationPending().then((result) => {
      if (active) setPending(result)
    })
    return () => {
      active = false
    }
  }, [])

  return pending && pathname !== INSTALLATION_ROUTE ? (
    <Navigate to={INSTALLATION_ROUTE} replace />
  ) : null
}
