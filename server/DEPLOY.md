=# 稳稳学英语 · 后台服务部署指南（VPS）

提供注册/登录、用户星星累计同步。SQLite 单文件数据库，无外部依赖。

## 方式一：Docker（推荐）

```bash
cd server
docker build -t ww-api .
docker run -d --name ww-api --restart always \
  -p 127.0.0.1:8600:8600 \
  -v /opt/ww/data:/data \
  ww-api
```

## 方式二：systemd + venv

```bash
cd /opt && mkdir -p ww && cp -r server ww/server && cd ww/server
python3 -m venv venv && ./venv/bin/pip install -r requirements.txt
# 建库
./venv/bin/python -c 'import app; app.init_db()'
```

`/etc/systemd/system/ww-api.service`：

```ini
[Unit]
Description=WenWen English API
After=network.target

[Service]
WorkingDirectory=/opt/ww/server
ExecStart=/opt/ww/server/venv/bin/gunicorn -w 2 -b 127.0.0.1:8600 --timeout 15 app:app
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now ww-api
```

## Caddy 反代（复用现有 jxonline.top 站点）

在 Caddyfile 的 `jxonline.top` 站点块内加（服务只监听 127.0.0.1，对外走现有 HTTPS）：

```caddy
jxonline.top {
    # ... 现有配置 ...

    handle_path /ww/* {
        reverse_proxy 127.0.0.1:8600
    }
}
```

重载：`caddy reload`（先 `caddy validate`）。
 App 端服务器地址填：`https://www.jxonline.top/ww`

## 自测

```bash
curl http://127.0.0.1:8600/api/ping
curl -X POST http://127.0.0.1:8600/api/register -H 'Content-Type: application/json' -d '{"username":"test123","password":"1234"}'
curl -X POST http://127.0.0.1:8600/api/login -H 'Content-Type: application/json' -d '{"username":"test123","password":"1234"}'
curl -X POST http://127.0.0.1:8600/api/stars/add -H 'Content-Type: application/json' -d '{"user_id":1,"add":5}'
curl "http://127.0.0.1:8600/api/stars?user_id=1"
```

数据文件：Docker 在 `/opt/ww/data/data.db`；systemd 在 `server/data.db`，备份该文件即可迁移。

按既有惯例：改配置走 备份 → 校验 → 重启 → 验证 → 失败回滚。

> AI生成
