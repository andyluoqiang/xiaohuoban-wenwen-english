# 稳稳学英语 · 后台服务部署指南（VPS）

提供注册/登录、用户星星累计同步。SQLite 单文件数据库，无外部依赖。
**已于 2026-11-12 部署上线**：https://www.jxonline.top/ww（App 登录页默认地址）

## 实际部署信息（本次已就位，勿重复执行）

- VPS：root@23.94.61.235（CentOS 7，只有 python2 → 本次 yum 装了 python3-3.6.8）
- 代码：`/opt/ww/server/`（app.py + requirements.txt + venv）
- 数据：`/opt/ww/data/data.db`（备份此文件即可迁移）
- 服务：systemd `ww-api`（gunicorn -w 2 -b 127.0.0.1:8600，自启）
- 反代：`/etc/caddy/sites/www.jxonline.top.conf` 站点块内 `handle_path /ww/* → 127.0.0.1:8600`
  - Caddy 是 233boy 脚本管理，**不要手工编辑主 Caddyfile**（只有 import）
  - 本机备份：`*.conf.bak-ww-20261112-092256` 等
- 注意：Caddyfile 全局 `admin off`，`caddy reload` 不可用，改配置用 `systemctl restart caddy`（执行前必须 caddy validate）

## 日常运维

```bash
systemctl status ww-api          # 服务状态
systemctl restart ww-api         # 改 app.py 后重启
journalctl -u ww-api -n 50       # 看日志
cp /opt/ww/data/data.db /root/   # 备份数据库
```

## 代码更新流程

```bash
# 本地改完 app.py 后（备份→上传→重启→验证）
scp app.py root@23.94.61.235:/opt/ww/server/app.py.new
ssh root@23.94.61.235 "cd /opt/ww/server && cp app.py app.py.bak.$(date +%Y%m%d-%H%M%S) && mv app.py.new app.py && systemctl restart ww-api && curl -s http://127.0.0.1:8600/api/ping"
```

## 冷重建（仅当服务器全重来时用）

```bash
# 1. python3（CentOS7 EOL，镜像失效则切 vault）
yum install -y python3 python3-pip || (
  sed -i -e 's|^mirrorlist=|#mirrorlist=|g' -e 's|^#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*.repo
  yum clean all && yum install -y python3 python3-pip )

# 2. 代码+venv（python3.6 装 flask 3.0 不兼容，用 2.0.3）
mkdir -p /opt/ww/server /opt/ww/data && cd /opt/ww/server
# 上传 app.py requirements.txt 后：
python3 -m venv venv
./venv/bin/pip install 'flask==2.0.3' 'werkzeug==2.0.3' 'gunicorn==20.1.0'
# python>=3.8 的机器直接./venv/bin/pip install -r requirements.txt

# 3. systemd（见下 service 内容）→ /etc/systemd/system/ww-api.service
# 4. Caddy sites/www.jxonline.top.conf 站点块尾加 handle_path /ww/*
# 5. caddy validate && systemctl restart caddy
```

`/etc/systemd/system/ww-api.service`：

```ini
[Unit]
Description=WenWen English API (ww-api)
After=network.target

[Service]
WorkingDirectory=/opt/ww/server
Environment=WW_DB=/opt/ww/data/data.db
ExecStart=/opt/ww/server/venv/bin/gunicorn -w 2 -b 127.0.0.1:8600 --timeout 15 app:app
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

## API 自测

```bash
curl https://www.jxonline.top/ww/api/ping
curl -X POST https://www.jxonline.top/ww/api/register -H 'Content-Type: application/json' -d '{"username":"test123","password":"1234"}'
curl -X POST https://www.jxonline.top/ww/api/login -H 'Content-Type: application/json' -d '{"username":"test123","password":"1234"}'
curl -X POST https://www.jxonline.top/ww/api/stars/add -H 'Content-Type: application/json' -d '{"user_id":1,"add":5}'
curl "https://www.jxonline.top/ww/api/stars?user_id=1"
```

按既有惯例：改配置走 备份 → 校验（validate）→ 重启 → 验证 → 失败回滚。

> AI生成
