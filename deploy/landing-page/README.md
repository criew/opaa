# Auslieferung von opaa.io

Der VPS spiegelt den öffentlichen Branch `gh-pages` (Landing Page und Tagesreport) per Pull in
sein Webverzeichnis, siehe [`page/README.md`](../../page/README.md#wo-das-landet). Hier liegen
die Dateien dafür; Zugangsdaten braucht keine davon.

| Datei | Ziel auf dem Server |
|---|---|
| `opaa-page-pull.sh` | `/usr/local/bin/opaa-page-pull.sh` |
| `opaa-page-pull.service`, `opaa-page-pull.timer` | `/etc/systemd/system/` |
| `opaa.io.nginx.conf` | `/etc/nginx/sites-available/opaa.io`, verlinkt nach `sites-enabled/` |

## Einrichtung

```bash
sudo apt-get install -y git rsync
sudo useradd --system --no-create-home --shell /usr/sbin/nologin opaa-page
sudo install -d -o opaa-page -g opaa-page /var/cache/opaa-page /var/www/opaa.io
sudo install -m 0755 opaa-page-pull.sh /usr/local/bin/
sudo install -m 0644 opaa-page-pull.service opaa-page-pull.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now opaa-page-pull.timer
sudo systemctl start opaa-page-pull.service    # erster Lauf sofort
journalctl -u opaa-page-pull.service -n 20

sudo install -m 0644 opaa.io.nginx.conf /etc/nginx/sites-available/opaa.io
sudo ln -s /etc/nginx/sites-available/opaa.io /etc/nginx/sites-enabled/opaa.io
sudo nginx -t && sudo systemctl reload nginx
# erst wenn die DNS-Einträge auf den Server zeigen:
sudo certbot --nginx -d opaa.io -d www.opaa.io --redirect
```

Eine Änderung an `page/` ist damit spätestens fünf Minuten nach dem Lauf des Workflows
„Landing Page“ online. Fällt GitHub aus, bleibt der zuletzt gespiegelte Stand stehen.
