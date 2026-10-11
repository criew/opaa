import { expect } from '@playwright/test'

/**
 * Bausteine für die echte Nextcloud der Suite (`nextcloud` in docker-compose.e2e.yml, test(e2e)
 * #2175). Alles läuft über die Schnittstellen, die auch ein Mensch in der Nextcloud hätte - die
 * Bereitstellungsschnittstelle für Konten, WebDAV für Dateien, `getapppassword` für das
 * App-Passwort -, nie über `occ`: So braucht die Suite keinen Zugriff auf den Container, und das
 * App-Passwort entsteht auf dem Weg, den das Handbuch der Person beschreibt.
 *
 * Playwright erreicht die Nextcloud unter dem Host-Port, den scripts/run-e2e.mjs als
 * E2E_NEXTCLOUD_BASE_URL durchreicht; das Backend erreicht sie im Compose-Netz als `nextcloud`
 * (siehe NEXTCLOUD_SERVER_URL).
 */

const BASE_URL = process.env.E2E_NEXTCLOUD_BASE_URL ?? 'http://localhost:18090'

/** Die Server-Adresse, die ein Zugang trägt: der Dienstname im Compose-Netz, ohne Schrägstrich. */
export const NEXTCLOUD_SERVER_URL = 'http://nextcloud'

const ADMIN = { user: 'admin', password: 'E2e-Nextcloud-Admin-2026!' }
// Nextcloud verlangt ein Passwort, das nicht in Wörterbüchern steht; die Konten sind wegwerfbar.
const ACCOUNT_PASSWORD = 'E2e-Nextcloud-Konto-2026!'

function basic(user: string, password: string): string {
  return `Basic ${Buffer.from(`${user}:${password}`).toString('base64')}`
}

/** Wartet, bis die Installation der Nextcloud abgeschlossen ist (erster Start: eine halbe Minute). */
export async function waitForNextcloud(timeoutMs = 240_000): Promise<void> {
  await expect
    .poll(
      async () => {
        try {
          const response = await fetch(`${BASE_URL}/status.php`)
          return response.ok && (await response.text()).includes('"installed":true')
        } catch {
          return false
        }
      },
      { timeout: timeoutMs, intervals: [2_000], message: 'Nextcloud wurde nicht bereit' },
    )
    .toBe(true)
}

async function ocs(path: string, init: RequestInit & { auth: string }): Promise<Response> {
  const { auth, headers, ...rest } = init
  return fetch(`${BASE_URL}${path}${path.includes('?') ? '&' : '?'}format=json`, {
    ...rest,
    headers: { Authorization: auth, 'OCS-APIRequest': 'true', ...headers },
  })
}

/** Ein Nextcloud-Konto mit einer Datei und dem App-Passwort, das eine Person in OPAA einträgt. */
export interface NextcloudAccount {
  user: string
  appPassword: string
}

/** Legt ein Konto an und erzeugt für es ein App-Passwort, wie die Person es in ihren Einstellungen täte. */
export async function createNextcloudAccount(user: string): Promise<NextcloudAccount> {
  const created = await ocs('/ocs/v1.php/cloud/users', {
    method: 'POST',
    auth: basic(ADMIN.user, ADMIN.password),
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ userid: user, password: ACCOUNT_PASSWORD }),
  })
  expect(created.ok, `Konto ${user} anlegen: ${created.status} ${await created.text()}`).toBe(true)

  const issued = await ocs('/ocs/v2.php/core/getapppassword', {
    auth: basic(user, ACCOUNT_PASSWORD),
  })
  expect(issued.ok, `App-Passwort für ${user}: ${issued.status}`).toBe(true)
  const body = (await issued.json()) as { ocs: { data: { apppassword: string } } }
  return { user, appPassword: body.ocs.data.apppassword }
}

export async function deleteNextcloudAccount(user: string): Promise<void> {
  const response = await ocs(`/ocs/v1.php/cloud/users/${encodeURIComponent(user)}`, {
    method: 'DELETE',
    auth: basic(ADMIN.user, ADMIN.password),
  })
  expect([200, 404], `Konto ${user} löschen: ${response.status}`).toContain(response.status)
}

/** Legt `folder` im Dateibaum von `user` an und darin die Datei `fileName` mit `text`. */
export async function putTextFile(
  account: NextcloudAccount,
  folder: string,
  fileName: string,
  text: string,
): Promise<void> {
  const auth = basic(account.user, ACCOUNT_PASSWORD)
  const root = `${BASE_URL}/remote.php/dav/files/${encodeURIComponent(account.user)}`
  const made = await fetch(`${root}/${encodeURIComponent(folder)}`, {
    method: 'MKCOL',
    headers: { Authorization: auth },
  })
  // 405: der Ordner besteht bereits.
  expect([201, 405], `MKCOL ${folder}: ${made.status}`).toContain(made.status)
  const put = await fetch(`${root}/${encodeURIComponent(folder)}/${encodeURIComponent(fileName)}`, {
    method: 'PUT',
    headers: { Authorization: auth, 'Content-Type': 'text/plain; charset=utf-8' },
    body: text,
  })
  expect([201, 204], `PUT ${fileName}: ${put.status}`).toContain(put.status)
}
