import { spawnSync } from "node:child_process";
import { expect, request, type APIRequestContext, type Page } from "@playwright/test";

/**
 * Helpers of the revision scenario of the local-auth target (12-revision.spec.ts).
 *
 * The role Revision exists only on local accounts in a stack without an identity provider, which is
 * why the scenario lives in this target and not in the dev-auth suite. Its preconditions - accounts,
 * a library, grants, role requests - go through the API with a bearer token from the local sign-in,
 * so only the revision's own pages are driven in the browser.
 */

const baseUrl = process.env.E2E_BASE_URL ?? "http://localhost:13001";

/** An API client signed in as one local account; its token carries a fresh auth_time. */
export async function apiSignedInAs(email: string, password: string): Promise<APIRequestContext> {
  const anonymous = await request.newContext({ baseURL: baseUrl });
  const login = await anonymous.post("/api/v1/auth/local/login", { data: { email, password } });
  expect(login.status(), await login.text()).toBe(200);
  const { accessToken } = (await login.json()) as { accessToken: string };
  await anonymous.dispose();
  return request.newContext({
    baseURL: baseUrl,
    extraHTTPHeaders: { Authorization: `Bearer ${accessToken}` },
  });
}

/**
 * Creates an ACTIVE local account and leaves it with `password`: the generated initial password is
 * changed through the API at once, since a token with a pending change is refused everywhere else.
 */
export async function createLocalAccount(
  admin: APIRequestContext,
  { email, displayName }: { email: string; displayName: string },
  password: string,
): Promise<string> {
  const created = await admin.post("/api/v1/admin/local-users", {
    data: {
      email,
      displayName,
      createdReason: "Prüfung der Revision im E2E-Lauf",
      mode: "INITIAL_PASSWORD",
      noExpiry: true,
    },
  });
  expect(created.status(), await created.text()).toBe(201);
  const body = (await created.json()) as { user: { id: string }; initialPassword: string };

  const anonymous = await request.newContext({ baseURL: baseUrl });
  const login = await anonymous.post("/api/v1/auth/local/login", {
    data: { email, password: body.initialPassword },
  });
  expect(login.status(), await login.text()).toBe(200);
  const { accessToken } = (await login.json()) as { accessToken: string };
  const changed = await anonymous.post("/api/v1/auth/local/change-password", {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { currentPassword: body.initialPassword, newPassword: password },
  });
  expect(changed.status(), await changed.text()).toBe(200);
  await anonymous.dispose();
  return body.user.id;
}

/** Asks for `role` as the system administration and returns the HTTP status of the answer. */
export async function changeRole(
  admin: APIRequestContext,
  userId: string,
  role: "USER" | "AUDITOR" | "SYSTEM_ADMIN",
): Promise<number> {
  const response = await admin.post(`/api/v1/admin/users/${userId}/role`, { data: { role } });
  expect([200, 202], await response.text()).toContain(response.status());
  return response.status();
}

/**
 * The backend's container log. The first auditor's password link goes there and nowhere else -
 * it reaches the operation, never the administration - so this is the one way a scenario gets it.
 */
function backendLog(): string {
  const project = process.env.E2E_COMPOSE_PROJECT ?? "opaa-local-auth";
  const ps = spawnSync(
    "docker",
    [
      "ps",
      "-q",
      "--filter",
      `label=com.docker.compose.project=${project}`,
      "--filter",
      "label=com.docker.compose.service=backend",
    ],
    { encoding: "utf8" },
  );
  const id = ps.stdout.trim().split(/\s+/)[0];
  if (!id) {
    throw new Error(`No backend container of Compose project ${project}: ${ps.stderr}`);
  }
  const logs = spawnSync("docker", ["logs", id], {
    encoding: "utf8",
    maxBuffer: 256 * 1024 * 1024,
  });
  return `${logs.stdout}${logs.stderr}`;
}

/** The path of the first auditor's password link from the server log, waited for after the commit. */
export async function firstAuditorLinkPath(): Promise<string> {
  let link = "";
  await expect
    .poll(
      () => {
        const match = /Link zum Setzen des Passworts: (\S+)/.exec(backendLog());
        link = match?.[1] ?? "";
        return link;
      },
      { timeout: 20_000 },
    )
    .not.toBe("");
  const url = new URL(link, baseUrl);
  return `${url.pathname}${url.search}`;
}

/** Sets the password on the page a password link opens. */
export async function setPasswordByLink(page: Page, path: string, password: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole("heading", { level: 1, name: "Passwort festlegen" })).toBeVisible();
  await page.locator("#set-password-new").fill(password);
  await page.locator("#set-password-repeat").fill(password);
  await page.getByRole("button", { name: "Passwort festlegen" }).click();
  await expect(
    page.getByText("Passwort festgelegt — Sie können sich jetzt anmelden."),
  ).toBeVisible();
}

/**
 * The value a datetime-local field takes for `epochMillis`, in the browser's own time zone and to
 * the second - the report forms read the field as local time.
 */
export async function localDateTime(page: Page, epochMillis: number): Promise<string> {
  return page.evaluate((millis) => {
    const d = new Date(millis);
    const pad = (n: number) => String(n).padStart(2, "0");
    return (
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
      `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    );
  }, epochMillis);
}

/** Fills the window and the reason of a report form and submits it. */
export async function fillReportWindow(
  page: Page,
  { from, to, reason }: { from: number; to: number; reason: string },
): Promise<void> {
  await page.getByLabel("Von").fill(await localDateTime(page, from));
  await page.getByLabel("Bis").fill(await localDateTime(page, to));
  await page.getByLabel("Anlass").fill(reason);
}

/** Waits until the wall clock has passed `epochMillis` by at least one full second. */
export async function passSecond(epochMillis: number): Promise<number> {
  const target = Math.floor(epochMillis / 1000) * 1000 + 1000;
  const wait = target - Date.now();
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait + 50));
  return Date.now();
}
