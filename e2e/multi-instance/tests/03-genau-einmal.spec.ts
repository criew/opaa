import { expect, test } from "@playwright/test";
import {
  bootstrapAdmin,
  callOk,
  corpusLibrary,
  createMailRecipient,
  loginOk,
  platform,
  prepareInstallation,
  settledMailCount,
  sleep,
  type Session,
} from "../../fixtures/multiInstance";

/**
 * Scenario 3 (ADR-0053, Entscheidungen 7 and 11): both instances tick every scheduled run and
 * both deliver the outbox, and still every due time starts one run and every mail leaves once.
 */

test.describe.configure({ mode: "serial" });

let a = "";
let b = "";
let admin: Session;

test.beforeAll(async () => {
  a = await platform().instanceUrl("a");
  b = await platform().instanceUrl("b");
  admin = await prepareInstallation(a);
});

test("jede Mail der Outbox geht genau einmal hinaus, gleich welche Instanz sie annahm", async () => {
  const recipient = await createMailRecipient(a, admin.token);
  const signIns = 20;
  // Each sign-in of the bootstrap administrator queues one mail to the recipient, alternately on
  // A and B, while the workers of both instances poll the outbox.
  await Promise.all(
    Array.from({ length: signIns }, (_, index) =>
      loginOk(index % 2 === 0 ? a : b, bootstrapAdmin.email, bootstrapAdmin.password),
    ),
  );
  expect(await settledMailCount(recipient, signIns)).toBe(signIns);
  expect(await platform().sql("SELECT count(*) FROM mail_outbox")).toBe("0");
});

test("ein geplanter Lauf startet je Fälligkeit genau einmal", async () => {
  // A daily schedule two minutes ahead, in the zone of the stack (UTC): both instances tick at the
  // top of that minute.
  const due = new Date(Date.now() + 2 * 60_000);
  due.setUTCSeconds(0, 0);
  const library = await callOk<{ id: string }>(a, "POST", "/api/v1/libraries", {
    token: admin.token,
    json: {
      ...corpusLibrary(`Geplant ${Date.now()}`, "vermerk-00"),
      schedule: { frequency: "DAILY", hour: due.getUTCHours(), minute: due.getUTCMinutes() },
    },
  });
  await sleep(Math.max(0, due.getTime() - Date.now()) + 75_000);

  const runs = async () =>
    (
      await callOk<{ runs: { triggeredBy: string; status: string }[] }>(
        b,
        "GET",
        `/api/v1/libraries/${library.id}/indexing/runs`,
        { token: admin.token },
      )
    ).runs;
  const deadline = Date.now() + 120_000;
  while ((await runs()).some((run) => run.status === "RUNNING") && Date.now() < deadline) {
    await sleep(2000);
  }
  const scheduled = (await runs()).filter((run) => run.triggeredBy === "SCHEDULED");
  expect(scheduled, JSON.stringify(await runs())).toHaveLength(1);
  expect(scheduled[0].status).toBe("COMPLETED");
});
