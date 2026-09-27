#!/usr/bin/env bash
# Transfers the demo-relevant parts of keycloak/realm-export.json into a Keycloak whose realm
# already exists - a Keycloak with its own volume never reads the export again. Idempotent: every
# step checks first and only creates or adds what is missing; running it twice changes nothing.
#
# What it brings in line with the export (realm "opaa" unless KC_REALM says otherwise):
#   - the groups "Bürgerbüro Rheinfurt", "Meldewesen", "Kfz-Zulassung" and their memberships,
#   - the confidential service-account client "opaa-directory" of the directory sync and exactly
#     the realm-management roles view-users and query-groups,
#   - the audience mapper "opaa-frontend-audience" on the seed client "opaa-seed".
# It never removes a group, a member, a role or a client, and it touches no password. The secret
# of opaa-directory is set when the client is created, and on an existing client only when
# DIRECTORY_CLIENT_SECRET is given - a rotated secret is never reset to the demo value.
#
# Usage (see demo/README.md, "Realm-Änderungen in ein bestehendes Keycloak übertragen"):
#   KC_CONTAINER=opaa-keycloak KC_SERVER=http://localhost:8180/idp \
#   KC_ADMIN_USER=admin KC_ADMIN_PASSWORD=... bash demo/keycloak/apply-realm-changes.sh
#
# KC_CONTAINER  runs kcadm.sh inside that container (docker exec); unset calls kcadm.sh from PATH
# KC_SERVER     Keycloak address as seen by kcadm (default http://localhost:8180; the public demo
#               instance serves Keycloak under /idp, also inside its container)
# KC_ADMIN_USER / KC_ADMIN_PASSWORD  an administrator of the master realm (required)
# KC_REALM      target realm (default opaa)
# DIRECTORY_CLIENT_SECRET  secret of opaa-directory; must match what OPAA stores as the
#               provider's directory connection. Unset: a new client gets the documented demo value
#               (local and CI stacks only), an existing one keeps its secret. A reachable instance
#               always passes its own generated value.

set -euo pipefail

KC_SERVER="${KC_SERVER:-http://localhost:8180}"
KC_REALM="${KC_REALM:-opaa}"
DIRECTORY_CLIENT_ID="opaa-directory"
if [ -n "${DIRECTORY_CLIENT_SECRET+x}" ] && [ -n "$DIRECTORY_CLIENT_SECRET" ]; then
  SECRET_GIVEN=true
else
  SECRET_GIVEN=false
  DIRECTORY_CLIENT_SECRET="RheinfurtVerzeichnis!2026"
fi
SEED_CLIENT_ID="opaa-seed"
AUDIENCE_MAPPER="opaa-frontend-audience"
AUDIENCE_CLIENT_ID="opaa-frontend"

REALM_GROUPS=(
  "Bürgerbüro Rheinfurt"
  "Meldewesen"
  "Kfz-Zulassung"
)

# username|group - the direct memberships of keycloak/realm-export.json
MEMBERSHIPS=(
  "demo-admin|Bürgerbüro Rheinfurt"
  "maria.weber|Bürgerbüro Rheinfurt"
  "maria.weber|Meldewesen"
  "selin.kaya|Bürgerbüro Rheinfurt"
  "selin.kaya|Meldewesen"
  "thomas.klein|Bürgerbüro Rheinfurt"
  "thomas.klein|Kfz-Zulassung"
  "andrea.vogt|Bürgerbüro Rheinfurt"
)

: "${KC_ADMIN_USER:?KC_ADMIN_USER setzen (Administrator des master-Realms)}"
: "${KC_ADMIN_PASSWORD:?KC_ADMIN_PASSWORD setzen}"

kc() {
  if [ -n "${KC_CONTAINER:-}" ]; then
    MSYS_NO_PATHCONV=1 docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
  else
    kcadm.sh "$@"
  fi
}

# Prints the id of the first object in a kcadm "--fields id --format csv --noquotes" answer.
first_id() {
  tr -d '\r' | head -n 1
}

kc config credentials --server "$KC_SERVER" --realm master \
  --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD" >/dev/null
echo "Angemeldet an $KC_SERVER, Realm $KC_REALM"

group_id() {
  kc get groups -r "$KC_REALM" -q search="$1" -q exact=true --fields id,name --format csv --noquotes \
    | tr -d '\r' | awk -F, -v name="$1" '$2 == name { print $1; exit }'
}

for group in "${REALM_GROUPS[@]}"; do
  if [ -n "$(group_id "$group")" ]; then
    echo "  Gruppe vorhanden: $group"
  else
    kc create groups -r "$KC_REALM" -s name="$group" >/dev/null
    echo "  Gruppe angelegt: $group"
  fi
done

for entry in "${MEMBERSHIPS[@]}"; do
  username="${entry%%|*}"
  group="${entry#*|}"
  user_id="$(kc get users -r "$KC_REALM" -q username="$username" -q exact=true --fields id \
    --format csv --noquotes | first_id)"
  if [ -z "$user_id" ]; then
    echo "  Konto fehlt im Realm, Mitgliedschaft übersprungen: $username → $group" >&2
    continue
  fi
  gid="$(group_id "$group")"
  if kc get "users/$user_id/groups" -r "$KC_REALM" --fields name --format csv --noquotes \
    | tr -d '\r' | grep -Fxq "$group"; then
    echo "  Mitglied: $username → $group"
  else
    kc update "users/$user_id/groups/$gid" -r "$KC_REALM" -s realm="$KC_REALM" \
      -s userId="$user_id" -s groupId="$gid" -n
    echo "  Mitglied aufgenommen: $username → $group"
  fi
done

client_uuid() {
  kc get clients -r "$KC_REALM" -q clientId="$1" --fields id --format csv --noquotes | first_id
}

directory_uuid="$(client_uuid "$DIRECTORY_CLIENT_ID")"
if [ -z "$directory_uuid" ]; then
  kc create clients -r "$KC_REALM" -s clientId="$DIRECTORY_CLIENT_ID" -s enabled=true \
    -s publicClient=false -s clientAuthenticatorType=client-secret \
    -s standardFlowEnabled=false -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true \
    -s secret="$DIRECTORY_CLIENT_SECRET" >/dev/null
  if [ "$SECRET_GIVEN" = true ]; then
    echo "  Client angelegt: $DIRECTORY_CLIENT_ID (Geheimnis aus DIRECTORY_CLIENT_SECRET)"
  else
    echo "  Client angelegt: $DIRECTORY_CLIENT_ID (dokumentierter Demo-Wert - nur für lokale Stacks)"
  fi
else
  kc update "clients/$directory_uuid" -r "$KC_REALM" -s enabled=true -s publicClient=false \
    -s standardFlowEnabled=false -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true
  if [ "$SECRET_GIVEN" = true ]; then
    kc update "clients/$directory_uuid" -r "$KC_REALM" -s secret="$DIRECTORY_CLIENT_SECRET"
    echo "  Client vorhanden, Geheimnis aus DIRECTORY_CLIENT_SECRET gesetzt: $DIRECTORY_CLIENT_ID"
  else
    echo "  Client vorhanden, Geheimnis unverändert: $DIRECTORY_CLIENT_ID"
  fi
fi

# add-roles is idempotent: a role the service account already holds stays as it is.
kc add-roles -r "$KC_REALM" --uusername "service-account-$DIRECTORY_CLIENT_ID" \
  --cclientid realm-management --rolename view-users --rolename query-groups
echo "  Rollen des Dienstkontos: view-users, query-groups (realm-management)"

seed_uuid="$(client_uuid "$SEED_CLIENT_ID")"
if [ -z "$seed_uuid" ]; then
  echo "  Client $SEED_CLIENT_ID fehlt, Audience-Mapper übersprungen" >&2
elif kc get "clients/$seed_uuid/protocol-mappers/models" -r "$KC_REALM" --fields name \
  --format csv --noquotes | tr -d '\r' | grep -Fxq "$AUDIENCE_MAPPER"; then
  echo "  Audience-Mapper vorhanden: $SEED_CLIENT_ID"
else
  kc create "clients/$seed_uuid/protocol-mappers/models" -r "$KC_REALM" \
    -s name="$AUDIENCE_MAPPER" -s protocol=openid-connect \
    -s protocolMapper=oidc-audience-mapper \
    -s "config.\"included.client.audience\"=$AUDIENCE_CLIENT_ID" \
    -s 'config."id.token.claim"=false' \
    -s 'config."access.token.claim"=true' \
    -s 'config."introspection.token.claim"=true' >/dev/null
  echo "  Audience-Mapper angelegt: $SEED_CLIENT_ID → $AUDIENCE_CLIENT_ID"
fi

echo "Realm $KC_REALM entspricht in diesen Punkten keycloak/realm-export.json."
