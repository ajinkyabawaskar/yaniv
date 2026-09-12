# Build & Deploy Guide

## Local Build

```bash
# Bump version (patch/minor/major)
./bump-version.sh patch

# Build frontend
cd frontend && npm run build && cd ..

# Build backend JAR
mvn clean package -DskipTests=true
```

## Deploy to Production

```bash
# Copy JAR to server
scp target/yanif-0.0.1-SNAPSHOT.jar ajinkya@20.198.4.81:/opt/yaniv/yaniv.jar

# SSH into server
ssh ajinkya@20.198.4.81

# Restart service
sudo systemctl restart yaniv

# Check status
sudo systemctl status yaniv

# View logs
tail -f /var/log/yaniv/app.log
```

### Production logging

Per-action lines (game actions, auto-play, timers) log at DEBUG, so the
default INFO output stays small. The systemd unit appends stdout to
`/var/log/yaniv/app.log`, which logback cannot rotate -- rotate it on the
server with logrotate (`copytruncate`, since the unit holds the file open).
This is a server-side `/etc` file, intentionally not kept in the repo:

```bash
sudo tee /etc/logrotate.d/yaniv > /dev/null <<'EOF'
/var/log/yaniv/app.log {
    daily
    rotate 30
    maxsize 100M
    copytruncate
    compress
    delaycompress
    missingok
    notifempty
}
EOF
```

To temporarily see the hot-path DEBUG lines, set
`logging.level.shop.abwork.yanif.websocket.GameStateController=DEBUG`
in `/opt/yaniv/application-prod.properties` and restart.

## Run Production Server

```bash
/usr/bin/java -Dspring.profiles.active=prod \
  -Dspring.config.location=file:/opt/yaniv/application-prod.properties \
  -jar /opt/yaniv/yaniv.jar
```

## Redis Tunnel (for local dev)

```bash
ssh -L 6379:127.0.0.1:6390 ajinkya@20.198.4.81
```

## MySQL Tunnel (for local dev)

```bash
ssh -L 3304:127.0.0.1:3306 ajinkya@20.198.4.81
```

## Config

Edit production config on server:
```bash
sudo nano /opt/yaniv/application-prod.properties
```

### Production server tuning (896MB box, few fast tables)

These live in server-side `/etc` files, recorded here so a rebuild stays fast:

- `yaniv.service` JVM flags: `-Xms256m -Xmx384m -XX:MaxMetaspaceSize=160m
  -XX:+UseSerialGC` (96m metaspace OOMs at boot; SerialGC fits 2 vCPU).
- `/etc/sysctl.d/99-yaniv.conf`: `vm.swappiness=10` — keeps heap in RAM,
  swaps file cache instead (default 60 paged the heap out).
- MySQL: `max_connections = 40` (dynamic `SET GLOBAL` + persisted in
  `/etc/mysql/mysql.conf.d/mysqld.cnf`); 128M buffer pool is plenty.
- Redis: `CONFIG SET maxmemory 64mb` + `maxmemory-policy allkeys-lru`
  (persisted via `CONFIG REWRITE`); snapshots carry their own 24h TTL.
- In-repo caps that ship with the JAR: Tomcat `threads.max=50` /
  `min-spare=5`, Hikari `maximum-pool-size=10` / `minimum-idle=2`
  (`src/main/resources/application.properties`).