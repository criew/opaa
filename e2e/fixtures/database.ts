import { execFileSync } from "node:child_process";

/**
 * Read-only SQL against the database of the running "e2e" stack, for facts the suite cannot reach
 * through the API with its dev accounts - the revision paths of io.opaa.audit need the AUDITOR
 * role, which no dev account holds and the system administration cannot grant alone.
 *
 * The container is looked up by its Compose labels, so this works under any project name
 * run-e2e.mjs or a developer chose (COMPOSE_PROJECT_NAME, default "opaa-e2e").
 */
export function queryDatabase(statement: string): string[] {
  const project = process.env.COMPOSE_PROJECT_NAME ?? "opaa-e2e";
  const container = execFileSync(
    "docker",
    [
      "ps",
      "-q",
      "--filter",
      `label=com.docker.compose.project=${project}`,
      "--filter",
      "label=com.docker.compose.service=postgres",
    ],
    { encoding: "utf8" },
  ).trim();
  if (container === "") {
    throw new Error(`no postgres container of Compose project "${project}" is running`);
  }
  const output = execFileSync(
    "docker",
    ["exec", container, "psql", "-U", "opaa", "-d", "opaa", "-At", "-c", statement],
    { encoding: "utf8" },
  );
  return output.split("\n").filter((line) => line !== "");
}
