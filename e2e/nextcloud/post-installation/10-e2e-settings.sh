#!/bin/sh
# Runs once after the installation and before the web server starts (hook of the nextcloud image).
# The scenarios refuse a wrong app password on purpose; brute-force throttling would delay every
# later login from the backend's address, and the rate limit of the provisioning API would refuse
# the accounts the scenarios create.
set -eu

# The image runs its hooks as the web server's own user, but occ refuses any other one.
occ() {
  if [ "$(id -u)" = "0" ]; then
    su -s /bin/sh www-data -c "php /var/www/html/occ $*"
  else
    php /var/www/html/occ "$@"
  fi
}

occ config:system:set auth.bruteforce.protection.enabled --value=false --type=boolean
occ config:system:set ratelimit.protection.enabled --value=false --type=boolean
