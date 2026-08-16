# Co-locating Application Server & Redis on Ubuntu/Debian

This guide details how to install, configure, and optimize Redis on the same virtual machine (VM) as your application server for minimal latency.

---

## 1. Installation & Initial Configuration

Update your system packages and install Redis:

```bash
sudo apt update
sudo apt install redis-server -y

```

Open the configuration file:

```bash
sudo nano /etc/redis/redis.conf

```

Set the following parameters to ensure security and prevent memory exhaustion:

```conf
bind 127.0.0.1 ::1
maxmemory 256mb
maxmemory-policy allkeys-lru

```

Enable and start the service:

```bash
sudo systemctl enable --now redis-server

```

---

## 2. Unix Domain Socket Optimization (Recommended)

Using Unix domain sockets instead of TCP loopback bypasses network stack overhead, reducing latency by 20–30%.

### Configure Redis Socket

Re-open `/etc/redis/redis.conf` and uncomment or add:

```conf
unixsocket /var/run/redis/redis.sock
unixsocketperm 770

```

Restart Redis to apply the changes:

```bash
sudo systemctl restart redis-server

```

### Configure User Permissions

Add your application's system user (e.g., `www-data` or your deployment user) to the `redis` group:

```bash
sudo usermod -aG redis $USER

```

To refresh group permissions in your **current active shell** without logging out, run:

```bash
newgrp redis

```

> **Note:** Any background system services running your app (e.g., systemd services) must be restarted to inherit the new group membership.

---

## 3. Verification

Test the connection using the Unix socket:

```bash
redis-cli -s /var/run/redis/redis.sock ping
# Expected output: PONG

```

---

## 4. Application Integration Examples

Update your application environment to connect via socket path rather than TCP host/port:

* **Node.js (`ioredis`)**
```javascript
const Redis = require('ioredis');
const redis = new Redis({ path: '/var/run/redis/redis.sock' });

```


* **Node.js (`node-redis` v4+)**
```javascript
const { createClient } = require('redis');
const client = createClient({ socket: { path: '/var/run/redis/redis.sock' } });
await client.connect();

```


* **Python (`redis-py`)**
```python
import redis
r = redis.Redis(unix_socket_path='/var/run/redis/redis.sock')

```



---

## Best Practices Checklist

| Area | Recommendation |
| --- | --- |
| **Memory Management** | Always set `maxmemory` to keep Redis from triggering the Linux OOM killer against your app process. |
| **Socket Access** | Use Unix domain sockets over TCP (`127.0.0.1:6379`) for local inter-process communication. |
| **Security** | Block port `6379` on external firewalls (UFW / Cloud Security Groups). Restrict local TCP access via `bind 127.0.0.1`. |