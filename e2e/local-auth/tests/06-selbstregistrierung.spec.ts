import { expect, test } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  enableLocalAccounts,
  openAccountList,
  openLocalAuthSettings,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";
import { clearMailbox, linkPathIn, mailCount, waitForMail } from "../../fixtures/mailpit";

/**
 * Scenario 6 of #1543: self-registration, from switching it on to the first sign-in.
 *
 * ADR-0033, Entscheidung 11: self-registration works only with a non-empty domain list - an empty
 * list means "nobody can register", not "anybody". The administration refuses to switch it on
 * without one, which this scenario asserts before it enters a domain. A registered account is not
 * able to sign in until the address is confirmed, and an address outside the list never creates an
 * account - with the same answer either way, because a different one would be an enumeration
 * channel.
 */
test.describe("Selbstregistrierung: Domänenliste, Bestätigung, Anmeldung", () => {
  const address = uniqueAddress("neue.kollegin");
  const outsideAddress = `fremd-${Date.now().toString(36)}@externe-domain.example`;
  const password = "Tannenwipfel-Uferweg-63";

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const admin = await context.newPage();
    acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await configureSmtp(admin);
    await enableLocalAccounts(admin);
    await context.close();
  });

  test("ohne Domänenliste lässt sich die Selbstregistrierung nicht einschalten", async ({
    page,
    browser,
  }) => {
    acceptConfirmDialogs(page);
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password);
    await openLocalAuthSettings(page);

    await page.locator("#local-auth-domains").fill("");
    await page.getByRole("switch", { name: "Selbstregistrierung" }).click();
    await expect(
      page.getByText(
        "Tragen Sie zuerst mindestens eine Adress-Domäne ein – ohne Domänenliste kann sich niemand registrieren.",
      ),
    ).toBeVisible();
    await expect(page.getByRole("switch", { name: "Selbstregistrierung" })).not.toBeChecked();
    // The public configuration still reports the flow as off, so /register redirects - asserted from
    // a context without a session, which is who would follow such a link.
    const visitorContext = await browser.newContext();
    const visitor = await visitorContext.newPage();
    await visitor.goto("/register");
    await expect(visitor).toHaveURL(/\/login(?:$|[?#])/);
    await visitorContext.close();
  });

  test("mit Domänenliste registriert sich eine Person, bestätigt die Adresse und meldet sich an", async ({
    browser,
  }) => {
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password);
    await openLocalAuthSettings(admin);

    await admin.locator("#local-auth-domains").fill("stadt.example");
    await admin.getByRole("switch", { name: "Selbstregistrierung" }).click();
    await expect(admin.getByText("Die Selbstregistrierung ist eingeschaltet.")).toBeVisible();
    await expect(admin.getByRole("switch", { name: "Selbstregistrierung" })).toBeChecked();

    const userContext = await browser.newContext();
    const user = await userContext.newPage();

    // An address outside the list gets the same 202-shaped answer and creates nothing.
    await clearMailbox();
    await user.goto("/register");
    await expect(user.getByRole("heading", { level: 1, name: "Konto registrieren" })).toBeVisible();
    await user.locator("#register-display-name").fill("Fremde Person");
    await user.locator("#register-email").fill(outsideAddress);
    await user.locator("#register-password").fill(password);
    await user.getByRole("button", { name: "Konto registrieren" }).click();
    await expect(
      user.getByText("Wenn zu dieser Adresse ein Konto besteht, haben wir eine E-Mail geschickt."),
    ).toBeVisible();

    // An address inside the list does create an account - unconfirmed, and therefore not yet able
    // to sign in.
    await user.goto("/register");
    await user.locator("#register-display-name").fill("Neue Kollegin");
    await user.locator("#register-email").fill(address);
    await user.locator("#register-password").fill(password);
    await user.getByRole("button", { name: "Konto registrieren" }).click();
    await expect(
      user.getByText("Wenn zu dieser Adresse ein Konto besteht, haben wir eine E-Mail geschickt."),
    ).toBeVisible();

    const confirmation = await waitForMail(address);
    const verifyPath = linkPathIn(confirmation);
    expect(verifyPath).toContain("/verify-email");

    // Erst hier ist bewiesen, dass die fremde Domäne oben **nichts** ausgelöst hat: Das Postfach hält
    // genau diese eine Nachricht. Eine Zählung direkt nach der abgelehnten Registrierung wäre schon
    // erfüllt gewesen, bevor irgendein Versand hätte stattfinden können.
    expect(await mailCount()).toBe(1);
    expect(confirmation.recipients.map((a) => a.toLowerCase())).toEqual([address.toLowerCase()]);

    await user.goto("/login");
    await user.getByLabel("E-Mail-Adresse").fill(address);
    await user.getByLabel("Passwort", { exact: true }).fill(password);
    await user.getByRole("button", { name: "Anmelden" }).click();
    await expect(
      user.getByText("Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort."),
    ).toBeVisible();

    await user.goto(verifyPath);
    await expect(
      user.getByText("E-Mail-Adresse bestätigt — Sie können sich jetzt anmelden."),
    ).toBeVisible();
    await expect.poll(() => user.url()).not.toContain("token=");

    await signInSuccessfully(user, address, password);
    await user.goto("/spaces");
    await expect(user.getByText("Meine Dokumente").first()).toBeVisible();

    // A self-registered account carries the fixed reason and a mandatory expiry date - the two
    // properties that keep self-registration from hollowing out the limit (Entscheidung 11).
    await user.goto("/settings");
    await expect(user.getByText("Anlass des Kontos")).toBeVisible();
    await expect(user.getByText("Selbstregistrierung")).toBeVisible();

    // Das selbstregistrierte Konto steht im Bereich „Konten", der Schalter im Bereich
    // „Einstellungen" (#1601) - zwei Routen, also zwei Aufrufe.
    await openAccountList(admin);
    const row = admin.getByRole("table", { name: "Konten" });
    await expect(row).toContainText("Neue Kollegin");

    // Switch it back off: it is off by default for a reason, and no later scenario expects it on.
    await openLocalAuthSettings(admin);
    await admin.getByRole("switch", { name: "Selbstregistrierung" }).click();
    await expect(admin.getByText("Die Selbstregistrierung ist abgeschaltet.")).toBeVisible();

    await userContext.close();
    await adminContext.close();
  });
});
