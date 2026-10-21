import { expect, test } from "@playwright/test";
import {
  aiStubInternalUrl,
  call,
  callOk,
  createActiveAccount,
  eventually,
  login,
  loginOk,
  logout,
  platform,
  prepareInstallation,
  sessionStatus,
  setLocalAccounts,
  uniqueAddress,
  type Session,
} from "../../fixtures/multiInstance";

/**
 * Scenario 2 (ADR-0053, Entscheidungen 5, 6 and 12): a change of rights, of the identity providers
 * and of sessions accepted by one instance holds on the other with its next request, not after a
 * cache lifetime or a restart. Every check reads on the instance that did not take the change.
 * The deadline of a few seconds covers the delivery of the notification after the commit; the
 * caches behind it live ten minutes and longer.
 */

test.describe.configure({ mode: "serial" });

const PASSWORD = "Zwei-Instanzen-Probe!2026";
const WITHIN_MS = 5_000;

let a = "";
let b = "";
let admin: Session;
let person: { id: string; email: string; session: Session };

test.beforeAll(async () => {
  a = await platform().instanceUrl("a");
  b = await platform().instanceUrl("b");
  admin = await prepareInstallation(a);
  const email = uniqueAddress("wirkung");
  const created = await createActiveAccount(a, admin.token, b, email, PASSWORD);
  person = { id: created.id, email, session: created.session };
});

test("eine Freigabe und ihr Entzug auf A gelten sofort auf B", async () => {
  const library = await callOk<{ id: string }>(a, "POST", "/api/v1/libraries", {
    token: admin.token,
    json: { name: `Wirkung ${Date.now()}`, sourceType: "UPLOAD" },
  });
  const readOnB = async () =>
    (await call(b, "GET", `/api/v1/libraries/${library.id}`, { token: person.session.token }))
      .status;
  // B has looked at the library for this person before the grant: its cache holds "no access".
  expect(await readOnB()).not.toBe(200);

  const grant = await callOk<{ id: string }>(
    a,
    "POST",
    `/api/v1/assets/KNOWLEDGE_LIBRARY/${library.id}/grants`,
    { token: admin.token, json: { subjectType: "USER", subjectId: person.id, role: "VIEWER" } },
  );
  expect(await eventually(async () => (await readOnB()) === 200, WITHIN_MS)).toBe(true);

  await callOk(a, "DELETE", `/api/v1/assets/KNOWLEDGE_LIBRARY/${library.id}/grants/${grant.id}`, {
    token: admin.token,
  });
  expect(await eventually(async () => (await readOnB()) !== 200, WITHIN_MS)).toBe(true);
});

test("Gruppenmitgliedschaft geändert auf B gilt sofort auf A", async () => {
  const group = await callOk<{ id: string }>(b, "POST", "/api/v1/groups", {
    token: admin.token,
    json: { name: `Vertretung ${Date.now()}`, releasedForUse: true },
  });
  const library = await callOk<{ id: string }>(b, "POST", "/api/v1/libraries", {
    token: admin.token,
    json: { name: `Gruppenwirkung ${Date.now()}`, sourceType: "UPLOAD" },
  });
  await callOk(b, "POST", `/api/v1/assets/KNOWLEDGE_LIBRARY/${library.id}/grants`, {
    token: admin.token,
    json: { subjectType: "GROUP", subjectId: group.id, role: "VIEWER" },
  });
  const readOnA = async () =>
    (await call(a, "GET", `/api/v1/libraries/${library.id}`, { token: person.session.token }))
      .status;
  expect(await readOnA()).not.toBe(200);

  await callOk(b, "POST", `/api/v1/groups/${group.id}/members`, {
    token: admin.token,
    json: { userId: person.id },
  });
  expect(await eventually(async () => (await readOnA()) === 200, WITHIN_MS)).toBe(true);

  await callOk(b, "DELETE", `/api/v1/groups/${group.id}/members/${person.id}`, {
    token: admin.token,
  });
  expect(await eventually(async () => (await readOnA()) !== 200, WITHIN_MS)).toBe(true);
});

test("Abmelden auf A beendet die Sitzung sofort auf B", async () => {
  const session = await loginOk(a, person.email, PASSWORD);
  expect(await sessionStatus(b, session.token)).toBe(200);
  await logout(a, session);
  expect(await sessionStatus(b, session.token)).toBe(401);
});

test("Sperren auf A beendet die Sitzungen auf B, Entsperren auf B lässt wieder herein", async () => {
  const session = await loginOk(b, person.email, PASSWORD);
  expect(await sessionStatus(b, session.token)).toBe(200);
  await callOk(a, "POST", `/api/v1/admin/local-users/${person.id}/lock`, {
    token: admin.token,
    json: {},
  });
  expect(await sessionStatus(b, session.token)).toBe(401);
  expect(await login(b, person.email, PASSWORD)).toBeNull();

  await callOk(b, "POST", `/api/v1/admin/local-users/${person.id}/unlock`, {
    token: admin.token,
  });
  person.session = await loginOk(a, person.email, PASSWORD);
});

test("Abschalten der lokalen Anmeldung auf B wirkt sofort auf A", async () => {
  expect(await sessionStatus(a, person.session.token)).toBe(200);
  await setLocalAccounts(b, admin.token, false);
  try {
    const config = await callOk<{ localAccounts: { enabled: boolean } }>(
      a,
      "GET",
      "/api/v1/auth/config",
    );
    expect(config.localAccounts.enabled).toBe(false);
    expect(await sessionStatus(a, person.session.token)).toBe(401);
    expect(await login(a, person.email, PASSWORD)).toBeNull();
  } finally {
    await setLocalAccounts(a, admin.token, true);
  }
  const config = await callOk<{ localAccounts: { enabled: boolean } }>(
    b,
    "GET",
    "/api/v1/auth/config",
  );
  expect(config.localAccounts.enabled).toBe(true);
  person.session = await loginOk(b, person.email, PASSWORD);
});

test("ein auf A angelegter Anmeldeanbieter wird auf B sofort neu aufgebaut", async () => {
  const provider = await callOk<{ id: string }>(a, "POST", "/api/v1/admin/oidc-providers", {
    token: admin.token,
    json: {
      displayName: `Partnerhaus ${Date.now()}`,
      // Reachable, but no issuer: every instance's own attempt fails and says why.
      issuerUri: `${aiStubInternalUrl()}/realms/partnerhaus-${Date.now()}`,
      clientId: "opaa",
    },
  });
  try {
    // The status of a provider is that of the answering instance. B names a reason only after it
    // rebuilt its registry, which the change on A has to trigger.
    const stateOn = async (url: string) => {
      const providers = await callOk<
        { id: string; registryState: string; registryMessage: string | null }[]
      >(url, "GET", "/api/v1/admin/oidc-providers", { token: admin.token });
      return providers.find((entry) => entry.id === provider.id);
    };
    expect(
      await eventually(async () => {
        const state = await stateOn(b);
        return state?.registryState === "UNAVAILABLE" && !!state.registryMessage;
      }, WITHIN_MS),
    ).toBe(true);
    expect((await stateOn(a))?.registryState).toBe("UNAVAILABLE");

    // The only provider beside the local accounts: switching it off needs the acknowledgement.
    await callOk(
      a,
      "POST",
      `/api/v1/admin/oidc-providers/${provider.id}/disable?acknowledgeLastProvider=true`,
      { token: admin.token },
    );
    expect(
      await eventually(async () => (await stateOn(b))?.registryState === "DISABLED", WITHIN_MS),
    ).toBe(true);
  } finally {
    await callOk(
      a,
      "DELETE",
      `/api/v1/admin/oidc-providers/${provider.id}?acknowledgeLastProvider=true`,
      { token: admin.token },
    );
  }
});

test("die Statusseite zeigt auf beiden Instanzen denselben Stand", async () => {
  const roles = async (url: string) => {
    const status = await callOk<{ modelRoles: { role: string; state: string; faulted: boolean }[] }>(
      url,
      "GET",
      "/api/v1/admin/search/status",
      { token: admin.token },
    );
    return status.modelRoles
      .map(({ role, state, faulted }) => ({ role, state, faulted }))
      .sort((left, right) => left.role.localeCompare(right.role));
  };
  // The first call probes and shares the result; the other instance shows the shared one.
  const fromA = await roles(a);
  expect(fromA.length).toBeGreaterThan(0);
  expect(await roles(b)).toEqual(fromA);
});
