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