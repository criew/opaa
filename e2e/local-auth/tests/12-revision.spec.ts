import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";
import { expectNoSeriousA11yViolations } from "../../fixtures/a11y";
import { createFromReview, openNewLibraryWizard } from "../../fixtures/chat";
import {
  bootstrapAdmin,
  enableLocalAccounts,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";
import {
  apiSignedInAs,
  changeRole,
  createLocalAccount,
  fillReportWindow,
  firstAuditorLinkPath,
  passSecond,
  setPasswordByLink,
} from "../../fixtures/revision";

/**
 * Revision and proof: the first auditor named by the operation, the four-eyes path of a role
 * request, the rights history before and after a revocation, the connection log of a source
 * connection and the checksum chain of the running month. Preconditions go through the API; the
 * browser drives the revision's own pages. Serial: every later test reads as the first auditor.
 *
 * Needs a fresh stack: the first auditor's address (OPAA_AUTH_FIRST_AUDITOR_EMAIL in local-auth.env)
 * becomes an auditor only while the organization has none.
 */
test.describe.serial("Revision und Nachweis", () => {
  const firstAuditor = {
    email: process.env.E2E_FIRST_AUDITOR_EMAIL || "revision@stadt.example",
    displayName: "Rita Revision",
    password: "Pruefpfad-Lindenblatt-7731",
  };
  const runId = Date.now().toString(36);
  let admin: APIRequestContext;

  test.beforeAll(async ({ browser }) => {
    // 05-abschalten switches the local account management off and on again; an aborted run of it
    // would leave every sign-in below refused.
    const context = await browser.newContext();
    const page = await context.newPage();
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await enableLocalAccounts(page);
    await context.close();
    admin = await apiSignedInAs(bootstrapAdmin.email, bootstrapAdmin.password);
  });

  test.afterAll(async () => {
    await admin?.dispose();
  });

  async function auditorPage(browser: Browser): Promise<Page> {
    const context = await browser.newContext();
    const page = await context.newPage();
    await signInSuccessfully(page, firstAuditor.email, firstAuditor.password);
    return page;
  }

  async function systemRoleOf(email: string, password: string): Promise<string> {
    const api = await apiSignedInAs(email, password);
    const me = (await (await api.get("/api/v1/auth/me")).json()) as { systemRole: string };
    await api.dispose();
    return me.systemRole;
  }

  test("1. Die erste Person der Revision erhält ihren Link über das Server-Log", async ({
    browser,
  }) => {
    const id = await createLocalAccount(admin, firstAuditor, "Vorlaeufig-Kennwort-1029");
    // Without an auditor nobody could confirm, so the operation's address takes the role at once.
    expect(await changeRole(admin, id, "AUDITOR")).toBe(200);

    const context = await browser.newContext();
    const page = await context.newPage();
    await setPasswordByLink(page, await firstAuditorLinkPath(), firstAuditor.password);
    await signInSuccessfully(page, firstAuditor.email, firstAuditor.password);
    await page.goto("/revision/rollenantraege");
    await expect(page.getByRole("heading", { level: 1, name: "Rollenanträge" })).toBeVisible();
    await expect(page.getByText("Es liegt kein Antrag vor.")).toBeVisible();
    await context.close();
  });

  test("2. Rollenantrag: Bestätigung nur durch die Revision, Ablehnung beendet ihn", async ({
    browser,
  }) => {
    const candidate = {
      email: uniqueAddress("revision-antrag"),
      displayName: `Bruno Bestaetigt ${runId}`,
    };
    const candidatePassword = "Kandidat-Nordwind-4410";
    const candidateId = await createLocalAccount(admin, candidate, candidatePassword);

    // The administration's grant becomes a request; the account keeps its role.
    expect(await changeRole(admin, candidateId, "AUDITOR")).toBe(202);
    expect(await systemRoleOf(candidate.email, candidatePassword)).toBe("USER");

    const auditorApi = await apiSignedInAs(firstAuditor.email, firstAuditor.password);
    const open = (await (await auditorApi.get("/api/v1/audit/auditor-role-requests")).json()) as {
      id: string;
      userId: string;
    }[];
    await auditorApi.dispose();
    const requestId = open.find((request) => request.userId === candidateId)?.id;
    expect(requestId).toBeTruthy();

    // Neither the person asked for nor the administration that asked can confirm.
    const self = await apiSignedInAs(candidate.email, candidatePassword);
    expect(
      (await self.post(`/api/v1/audit/auditor-role-requests/${requestId}/confirm`)).status(),
    ).toBe(403);
    await self.dispose();
    expect(
      (await admin.post(`/api/v1/audit/auditor-role-requests/${requestId}/confirm`)).status(),
    ).toBe(403);

    const auditor = await auditorPage(browser);
    await auditor.goto("/revision/rollenantraege");
    const requests = auditor.getByRole("table", { name: "Offene Anträge auf die Rolle Revision" });
    const row = requests.getByRole("row").filter({ hasText: candidate.displayName });
    await expect(row).toContainText(candidate.email);
    await expect(row).toContainText("Lokales Konto");
    await expect(row).toContainText("Systemverwaltung");
    await expectNoSeriousA11yViolations(auditor, "Revision: offene Rollenanträge");

    await auditor
      .getByRole("button", { name: `Antrag für ${candidate.displayName} bestätigen` })
      .click();
    // The page was loaded after the sign-in, so its token came from the refresh cookie and names
    // no sign-in time: granting a system role asks for the password first.
    const stepUp = auditor.getByRole("dialog", { name: "Anmeldung bestätigen" });
    await stepUp.getByLabel("Ihr Passwort").fill(firstAuditor.password);
    await stepUp.getByRole("button", { name: "Bestätigen" }).click();
    const dialog = auditor.getByRole("dialog", {
      name: `Link zum Passwort für „${candidate.displayName}“`,
    });
    await expect(dialog).toBeVisible();
    const link = (await dialog.getByTestId("auditor-setup-link-value").innerText()).trim();
    expect(link).toContain("/set-password");
    await dialog.getByRole("button", { name: "Schließen" }).click();
    await expect(auditor.getByText("Es liegt kein Antrag vor.")).toBeVisible();

    // The confirmation discards the old password; the link is the only way in.
    const anonymous = await browser.newContext();
    const login = await anonymous.request.post("/api/v1/auth/local/login", {
      data: { email: candidate.email, password: candidatePassword },
    });
    expect(login.status()).toBe(401);
    const newPassword = "Revision-Silberpappel-8820";
    const url = new URL(link, "http://placeholder");
    await setPasswordByLink(await anonymous.newPage(), `${url.pathname}${url.search}`, newPassword);
    await anonymous.close();
    expect(await systemRoleOf(candidate.email, newPassword)).toBe("AUDITOR");

    // A declined request ends without the role.
    const declined = {
      email: uniqueAddress("revision-abgelehnt"),
      displayName: `Dora Abgelehnt ${runId}`,
    };
    const declinedPassword = "Abgelehnt-Moorbirke-3307";
    const declinedId = await createLocalAccount(admin, declined, declinedPassword);
    expect(await changeRole(admin, declinedId, "AUDITOR")).toBe(202);
    await auditor.reload();
    await auditor
      .getByRole("button", { name: `Antrag für ${declined.displayName} ablehnen` })
      .click();
    await expect(
      auditor.getByText(`Der Antrag für „${declined.displayName}“ ist abgelehnt.`),
    ).toBeVisible();
    await expect(auditor.getByText("Es liegt kein Antrag vor.")).toBeVisible();
    expect(await systemRoleOf(declined.email, declinedPassword)).toBe("USER");
    await auditor.context().close();
  });

  test("3. Rechtehistorie: Freigabe und Entzug, Stichtag davor und danach", async ({ browser }) => {
    const libraryName = `Revision Akten ${runId}`;
    const reader = { email: uniqueAddress("revision-leser"), displayName: `Lena Leserin ${runId}` };
    const readerId = await createLocalAccount(admin, reader, "Leserin-Haselnuss-5126");
    const created = await admin.post("/api/v1/libraries", {
      data: { name: libraryName, sourceType: "UPLOAD" },
    });
    expect(created.status(), await created.text()).toBe(201);
    const libraryId = ((await created.json()) as { id: string }).id;

    const start = Date.now() - 1000;
    const granted = await admin.post(`/api/v1/assets/KNOWLEDGE_LIBRARY/${libraryId}/grants`, {
      data: { subjectType: "USER", subjectId: readerId, role: "VIEWER" },
    });
    expect(granted.status(), await granted.text()).toBeLessThan(300);
    const grantId = ((await granted.json()) as { id: string }).id;
    // The forms take whole seconds: the instant between grant and revocation lies a second later.
    const beforeRevocation = await passSecond(Date.now());
    const revoked = await admin.delete(
      `/api/v1/assets/KNOWLEDGE_LIBRARY/${libraryId}/grants/${grantId}`,
    );
    expect(revoked.status(), await revoked.text()).toBe(204);
    const afterRevocation = await passSecond(Date.now());

    const auditor = await auditorPage(browser);
    await auditor.goto("/revision/rechtehistorie");
    await expect(
      auditor.getByRole("heading", { level: 1, name: "Stichtagsauskunft" }),
    ).toBeVisible();
    await auditor.getByLabel("Kennung des Objekts").fill(libraryId);
    const readerRow = auditor.getByRole("row").filter({ hasText: reader.displayName });
    const submit = auditor.getByRole("button", { name: "Auskunft erstellen" });

    // Until the revocation the reader is on record, with the basis and the time of the grant.
    await fillReportWindow(auditor, {
      from: start,
      to: beforeRevocation,
      reason: "E2E Stichtag vor Entzug",
    });
    await submit.click();
    await expect(
      auditor.getByText(new RegExp(`^${libraryName} — \\d+ Zugriffszeitr`)),
    ).toBeVisible();
    await expect(readerRow).toContainText("Direkte Freigabe");
    await expect(readerRow).toContainText(/\d{1,2}\.\d{1,2}\.\d{4}/);

    // Over the whole window the reader's period has ended - it is no longer in effect.
    await fillReportWindow(auditor, {
      from: start,
      to: afterRevocation,
      reason: "E2E Gesamtzeitraum",
    });
    await submit.click();
    await expect(readerRow).toContainText("Direkte Freigabe");
    await expect(readerRow).not.toContainText("noch wirksam");
    await expectNoSeriousA11yViolations(auditor, "Revision: Stichtagsauskunft mit Ergebnis");

    // After the revocation only the owner remains.
    await fillReportWindow(auditor, {
      from: afterRevocation,
      to: afterRevocation + 30_000,
      reason: "E2E Stichtag nach Entzug",
    });
    await submit.click();
    await expect(auditor.getByRole("row").filter({ hasText: "Systemverwaltung" })).toBeVisible();
    await expect(readerRow).toHaveCount(0);
    await auditor.context().close();

    await admin.delete(`/api/v1/libraries/${libraryId}`);
  });

  test("4. Verbindungsprotokoll: eine Quelle wird über einen Zugang verbunden und endet", async ({
    browser,
  }) => {
    test.setTimeout(120_000);
    const profileName = `Revision Dropbox ${runId}`;
    // No spaces, as in dropbox-source-connect.spec.ts: the wizard's naming stays the same.
    const libraryName = `Revision-Dropbox-${runId}`;
    const profile = await admin.post("/api/v1/admin/connection-profiles", {
      data: {
        name: profileName,
        sourceType: "DROPBOX",
        serverUrl: "http://dropbox-stub:8090",
        authMethod: "OAUTH",
        ownership: "LIBRARY",
        clientId: "e2e-app-key",
        clientSecret: "e2e-app-secret",
      },
    });
    expect(profile.status(), await profile.text()).toBe(201);
    const profileId = ((await profile.json()) as { id: string }).id;
    const start = Date.now() - 1000;

    const context = await browser.newContext();
    const page = await context.newPage();
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await openNewLibraryWizard(page);
    await page.getByRole("radio", { name: /Dropbox/ }).click();
    await page.getByRole("button", { name: "Weiter", exact: true }).click();
    const consent = page.getByTestId("source-consent");
    await consent
      .getByRole("checkbox", { name: "Ich verbinde ein Dienstkonto, kein persönliches Konto" })
      .check();
    await consent.getByRole("button", { name: "Quelle verbinden" }).click();
    await expect(page.getByRole("radio", { name: "Nur ausgewählte Ordner" })).toBeVisible({
      timeout: 30_000,
    });
    await page.getByRole("radio", { name: "Nur ausgewählte Ordner" }).check();
    await page
      .getByRole("group", { name: "Ordner" })
      .getByRole("checkbox", { name: /E2E-Ablage/ })
      .check();
    await page.getByRole("button", { name: "Ändern Zeitplan" }).click();
    await page.getByRole("switch", { name: "Erste Indizierung sofort nach dem Anlegen" }).uncheck();
    await page.getByRole("button", { name: "Weiter", exact: true }).click();
    await page.getByLabel("Name", { exact: true }).fill(libraryName);
    await page.getByRole("button", { name: "Weiter", exact: true }).click();
    await createFromReview(page);
    const libraryId = new URL(page.url()).pathname.split("/").pop()!;
    await context.close();

    // Deleting the library ends the connection; both ends of the run are logged.
    const deleted = await admin.delete(`/api/v1/libraries/${libraryId}`);
    expect(deleted.status(), await deleted.text()).toBeLessThan(300);
    const end = await passSecond(Date.now());

    const auditor = await auditorPage(browser);
    await auditor.goto("/revision/verbindungsprotokoll");
    await expect(
      auditor.getByRole("heading", { level: 1, name: "Verbindungsprotokoll" }),
    ).toBeVisible();
    await fillReportWindow(auditor, { from: start, to: end, reason: "E2E Verbindungsprotokoll" });
    await auditor.getByLabel("Zugang (optional)").click();
    await auditor.getByRole("option", { name: profileName }).click();
    await auditor.getByRole("button", { name: "Protokoll anzeigen" }).click();

    const entries = auditor.getByRole("table", { name: "Einträge des Verbindungsprotokolls" });
    const connected = entries.getByRole("row").filter({ hasText: "Verbunden" });
    await expect(connected).toHaveCount(1);
    await expect(connected).toContainText(
      `Bibliothek ${libraryId} · Konto dienstkonto@example.org`,
    );
    await expect(connected).toContainText(profileName);
    await expect(connected).toContainText("Person");
    const ended = entries.getByRole("row").filter({ hasText: "Bibliothek gelöscht" });
    await expect(ended).toHaveCount(1);
    await expect(ended).toContainText("Gelöscht");
    await expectNoSeriousA11yViolations(auditor, "Revision: Verbindungsprotokoll mit Einträgen");
    await auditor.context().close();

    const removed = await admin.delete(`/api/v1/admin/connection-profiles/${profileId}`);
    expect(removed.status(), await removed.text()).toBeLessThan(300);
  });

  test("5. Die Prüfsummenkette des laufenden Monats ist konsistent", async () => {
    const month = new Date().toISOString().slice(0, 7);
    const path = `/api/v1/audit/chain/${month}?reason=${encodeURIComponent("E2E Kettenprüfung")}`;

    // The administration does not read the audit log, not even its chain.
    expect((await admin.get(path)).status()).toBe(403);

    const auditorApi = await apiSignedInAs(firstAuditor.email, firstAuditor.password);
    const response = await auditorApi.get(path);
    expect(response.status(), await response.text()).toBe(200);
    const chain = (await response.json()) as {
      month: string;
      status: string;
      sealed: boolean;
      chainedEntries: number;
      unchainedEntries: number;
      algorithm: string;
      deviation: unknown;
    };
    await auditorApi.dispose();
    expect(chain.month).toBe(month);
    // The chain run writes a checkpoint every ten minutes: a young stack may have none yet.
    expect(["INTACT", "UNCHAINED"]).toContain(chain.status);
    expect(chain.deviation ?? null).toBeNull();
    expect(chain.sealed).toBe(false);
    expect(chain.chainedEntries + chain.unchainedEntries).toBeGreaterThan(0);
    expect(chain.algorithm).toBeTruthy();
  });
});
