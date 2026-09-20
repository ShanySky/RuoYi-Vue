"""为专用验证机配置独立 MySQL/Redis，凭据只保存在任务私有目录。"""

import argparse
import json
from pathlib import Path
import secrets
import re
import subprocess
import time

import pymysql
from ai_evolution_vm import EVIDENCE, connect

ROOT = Path(__file__).resolve().parents[2]
SECRETS = EVIDENCE / "local-secrets.json"


def credentials():
    if not SECRETS.exists():
        SECRETS.write_text(json.dumps({"mysql": secrets.token_urlsafe(24),
                                      "encryption": secrets.token_urlsafe(32)}), encoding="utf-8")
        account = subprocess.check_output(["whoami"], text=True).strip()
        subprocess.run(["icacls", str(SECRETS), "/inheritance:r", "/grant:r", f"{account}:(F)"],
                       check=True, capture_output=True)
    value = json.loads(SECRETS.read_text(encoding="utf-8"))
    if "modelProxy" not in value:
        value["modelProxy"] = secrets.token_urlsafe(32)
        SECRETS.write_text(json.dumps(value), encoding="utf-8")
    return value


def run(client, command):
    _, stdout, stderr = client.exec_command(command, timeout=900)
    while not stdout.channel.exit_status_ready():
        if stdout.channel.recv_ready():
            print(stdout.channel.recv(65536).decode("utf-8"), end="", flush=True)
        if stderr.channel.recv_stderr_ready():
            print(stderr.channel.recv_stderr(65536).decode("utf-8"), end="", flush=True)
        time.sleep(0.2)
    print(stdout.read().decode("utf-8"), end="")
    print(stderr.read().decode("utf-8"), end="")
    code = stdout.channel.recv_exit_status()
    if code:
        raise RuntimeError(f"任务环境命令失败，退出码 {code}")


def provision(install=True):
    value = credentials()
    with connect() as client:
        if install:
            run(client, "sudo timeout 240 apt-get update -qq")
            run(client, "sudo timeout 600 env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker.io")
            run(client, "sudo systemctl enable --now docker")
        with client.open_sftp() as sftp:
            with sftp.file("/home/ruoyitest/mysql.env", "w") as stream:
                stream.write(f"MYSQL_ROOT_PASSWORD={value['mysql']}\nMYSQL_ROOT_HOST=%\n")
            sftp.chmod("/home/ruoyitest/mysql.env", 0o600)
        run(client, "sudo timeout 420 docker pull public.ecr.aws/docker/library/mysql:8.0")
        run(client, "sudo timeout 240 docker pull public.ecr.aws/docker/library/redis:7.4")
        run(client, "sudo docker run -d --name ai-evo-mysql --cpus 1.5 --memory 1536m --pids-limit 256 "
                    "--log-driver local --log-opt max-size=10m --log-opt max-file=2 "
                    "--env-file /home/ruoyitest/mysql.env -p 3306:3306 -v ai-evo-mysql:/var/lib/mysql public.ecr.aws/docker/library/mysql:8.0")
        run(client, "sudo docker run -d --name ai-evo-redis --cpus 0.5 --memory 256m --pids-limit 64 "
                    "--log-driver local --log-opt max-size=10m --log-opt max-file=2 "
                    "-p 6379:6379 public.ecr.aws/docker/library/redis:7.4 redis-server --maxmemory 128mb --maxmemory-policy noeviction")
        run(client, "sudo docker ps --format '{{.Names}} {{.Image}} {{.Status}}'")


def seed(name, revision):
    if name not in ("ruoyi_ai_baseline", "ruoyi_ai_evolution", "ruoyi_ai_upgrade") \
            and not re.fullmatch(r"ruoyi_ai_regression_[0-9]{8}_[0-9]{6}", name):
        raise RuntimeError("只能初始化本任务明确命名的数据库")
    value = credentials()
    with pymysql.connect(host="127.0.0.1", port=13392, user="root", password=value["mysql"],
                         charset="utf8mb4", autocommit=True,
                         client_flag=pymysql.constants.CLIENT.MULTI_STATEMENTS) as connection:
        with connection.cursor() as cursor:
            cursor.execute("select schema_name from information_schema.schemata where schema_name=%s", (name,))
            if cursor.fetchone():
                raise RuntimeError("目标数据库已存在，禁止覆盖；请核实恢复点")
            cursor.execute(f"create database `{name}` character set utf8mb4 collate utf8mb4_unicode_ci")
            cursor.execute(f"use `{name}`")
            for path in ("sql/ry_20260417.sql", "sql/quartz.sql", "sql/ai_fresh_install.sql"):
                if revision:
                    content = subprocess.check_output(["git", "show", f"{revision}:{path}"], cwd=ROOT).decode("utf-8")
                else:
                    content = (ROOT / path).read_text(encoding="utf-8-sig")
                cursor.execute(content)
                while cursor.nextset():
                    pass
            cursor.execute("update sys_config set config_value='false' where config_key='sys.account.captchaEnabled'")
            cursor.execute("insert ignore into sys_role_menu(role_id,menu_id) values(2,118),(2,1061)")
            cursor.execute("insert into ai_conversation(user_id,model_id,reasoning_effort,title,route,status,create_time,update_time) "
                           "values(1,null,null,'H_EXPIRED_CLEANUP','/index','ACTIVE',"
                           "date_sub(sysdate(),interval 120 day),date_sub(sysdate(),interval 120 day))")
            print(json.dumps({"database": name, "schema_revision": revision or "working-tree", "seeded": True}))


def schema_acceptance():
    remote = "/home/ruoyitest/ai-evolution-acceptance"
    files = list(ROOT.glob("sql/*.sql")) + list(ROOT.glob("sql/ai_migrations/*.sql"))
    files += [ROOT / path for path in (
        "scripts/ci/ai-schema-acceptance.sh",
        ".github/workflows/ai-agent-public-preview.yml", ".github/workflows/ai-agent-real-gpt.yml")]
    with connect() as client:
        with client.open_sftp() as sftp:
            def directory(path):
                try:
                    sftp.stat(path)
                except FileNotFoundError:
                    directory(path.rsplit("/", 1)[0])
                    sftp.mkdir(path)
            directory(remote + "/bin")
            for source in files:
                destination = remote + "/" + source.relative_to(ROOT).as_posix()
                directory(destination.rsplit("/", 1)[0])
                with sftp.file(destination, "w") as stream:
                    stream.write(source.read_text(encoding="utf-8-sig"))
            with sftp.file(remote + "/bin/mysql", "w") as stream:
                stream.write('#!/bin/sh\nexec sudo docker exec -i ai-evo-mysql mysql "$@"\n')
            sftp.chmod(remote + "/bin/mysql", 0o700)
        run(client, f"cd {remote} && set -a && . /home/ruoyitest/mysql.env && "
                    'export AI_CI_DB_PASSWORD="$MYSQL_ROOT_PASSWORD" && '
                    f'PATH="{remote}/bin:$PATH" bash scripts/ci/ai-schema-acceptance.sh')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("provision", "services", "seed", "schema"))
    parser.add_argument("--database", default="ruoyi_ai_evolution")
    parser.add_argument("--revision")
    args = parser.parse_args()
    if args.action in ("provision", "services"):
        provision(args.action == "provision")
    elif args.action == "schema":
        schema_acceptance()
    else:
        seed(args.database, args.revision)
