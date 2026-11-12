#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
稳稳学英语 · Android 端后台服务（部署于 VPS）
功能：账号注册/登录（字母数字账号密码）、用户星星累计同步。
数据库：SQLite 单文件（data.db），零外部依赖。
监听：127.0.0.1:8600（由 Caddy/Nginx 反代对外提供 HTTPS）。

API（前缀 /api，JSON）：
  GET  /api/ping                       → {code:0, msg:"pong"}
  POST /api/register {username,password} → {code:0,user_id,stars}  code:1=格式非法 2=账号已存在
  POST /api/login    {username,password} → {code:0,user_id,stars}  code:1=格式非法 2=密码错误或账号不存在
  POST /api/stars/add {user_id,add}      → {code:0,total}
  GET  /api/stars?user_id=N              → {code:0,total}
"""
import hashlib
import os
import re
import secrets
import sqlite3
import time

from flask import Flask, g, jsonify, request

DB_PATH = os.environ.get("WW_DB", os.path.join(os.path.dirname(os.path.abspath(__file__)), "data.db"))
NAME_RE = re.compile(r"^[A-Za-z0-9]{3,20}$")   # 账号：字母数字 3-20 位（可纯数字如手机号）
PWD_RE = re.compile(r"^[A-Za-z0-9]{4,20}$")    # 密码：字母数字 4-20 位（按用户要求不做复杂性要求）

app = Flask(__name__)


# ---------- 数据库 ----------
def db():
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH)
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(_exc):
    conn = g.pop("db", None)
    if conn is not None:
        conn.close()


def init_db():
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """CREATE TABLE IF NOT EXISTS users(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                salt TEXT NOT NULL,
                pwd_hash TEXT NOT NULL,
                stars INTEGER NOT NULL DEFAULT 0,
                created_at REAL NOT NULL)"""
        )


def hash_pwd(salt: str, pwd: str) -> str:
    return hashlib.sha256((salt + pwd).encode("utf-8")).hexdigest()


# ---------- API ----------
def ok(**kw):
    return jsonify({"code": 0, "msg": "ok", **kw})


def err(code, msg):
    return jsonify({"code": code, "msg": msg})


@app.get("/api/ping")
def ping():
    return ok(msg="pong")


@app.post("/api/register")
def register():
    d = request.get_json(silent=True) or {}
    username, password = str(d.get("username", "")), str(d.get("password", ""))
    if not NAME_RE.match(username) or not PWD_RE.match(password):
        return err(1, "账号需3-20位字母数字，密码需4-20位字母数字")
    conn = db()
    if conn.execute("SELECT id FROM users WHERE username=?", (username,)).fetchone():
        return err(2, "账号已存在，请直接登录")
    salt = secrets.token_hex(8)
    cur = conn.execute(
        "INSERT INTO users(username, salt, pwd_hash, stars, created_at) VALUES(?,?,?,?,?)",
        (username, salt, hash_pwd(salt, password), 0, time.time()),
    )
    conn.commit()
    return ok(user_id=cur.lastrowid, stars=0)


@app.post("/api/login")
def login():
    d = request.get_json(silent=True) or {}
    username, password = str(d.get("username", "")), str(d.get("password", ""))
    if not NAME_RE.match(username) or not PWD_RE.match(password):
        return err(1, "账号或密码格式不正确")
    row = db().execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
    if not row or row["pwd_hash"] != hash_pwd(row["salt"], password):
        return err(2, "账号或密码错误")
    return ok(user_id=row["id"], stars=row["stars"])


@app.post("/api/stars/add")
def stars_add():
    d = request.get_json(silent=True) or {}
    try:
        user_id, add = int(d.get("user_id", 0)), int(d.get("add", 0))
    except (TypeError, ValueError):
        return err(1, "参数错误")
    if user_id <= 0 or add <= 0:
        return err(1, "参数错误")
    conn = db()
    row = conn.execute("SELECT id FROM users WHERE id=?", (user_id,)).fetchone()
    if not row:
        return err(2, "用户不存在")
    conn.execute("UPDATE users SET stars=stars+? WHERE id=?", (add, user_id))
    conn.commit()
    total = conn.execute("SELECT stars FROM users WHERE id=?", (user_id,)).fetchone()["stars"]
    return ok(total=total)


@app.get("/api/stars")
def stars_get():
    try:
        user_id = int(request.args.get("user_id", "0"))
    except ValueError:
        return err(1, "参数错误")
    row = db().execute("SELECT stars FROM users WHERE id=?", (user_id,)).fetchone()
    if not row:
        return err(2, "用户不存在")
    return ok(total=row["stars"])


# 模块加载即建表（幂等 CREATE IF NOT EXISTS）：gunicorn 多 worker / test_client 均适用
init_db()

if __name__ == "__main__":
    # 仅本机监听，对外由 Caddy 反代提供 HTTPS
    app.run(host="127.0.0.1", port=8600, threaded=True)
