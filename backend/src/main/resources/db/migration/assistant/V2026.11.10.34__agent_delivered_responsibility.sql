SET LOCAL lock_timeout = '5s';

-- The delivered agent "Recherche" answers to the system administration as its responsible unit.
UPDATE asset_responsibilities
SET responsible_function = 'Systemverwaltung'
WHERE responsible_group_id IS NULL
  AND responsible_function IS NULL
  AND asset_id IN (SELECT id FROM agents WHERE seed_key IS NOT NULL);
