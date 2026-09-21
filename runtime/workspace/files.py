"""容器内固定文件桥：逐级拒绝链接，仅处理当前任务的正规文件。"""

import base64
import json
import os
from pathlib import PurePosixPath
import stat
import sys

LIMIT = 8 * 1024 * 1024


def open_file(name, write=False):
    path = PurePosixPath(name)
    if not name or len(name) > 240 or path.is_absolute() or ".." in path.parts or "\\" in name or "\x00" in name:
        raise ValueError("文件路径无效")
    parent = os.open("/work", os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in path.parts[:-1]:
            next_parent = os.open(part, os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            os.close(parent)
            parent = next_parent
        flags = os.O_NOFOLLOW | os.O_NONBLOCK | (os.O_WRONLY | os.O_CREAT | os.O_EXCL if write else os.O_RDONLY)
        descriptor = os.open(path.name, flags, 0o600, dir_fd=parent)
        attributes = os.fstat(descriptor)
        if not stat.S_ISREG(attributes.st_mode) or attributes.st_nlink != 1 or attributes.st_size > LIMIT:
            os.close(descriptor)
            raise ValueError("只允许限额内的正规单链接文件")
        return descriptor
    finally:
        os.close(parent)


if __name__ == "__main__":
    try:
        action, name = sys.argv[1:]
        if action == "read":
            with os.fdopen(open_file(name), "rb") as stream:
                data = stream.read(LIMIT + 1)
            if len(data) > LIMIT:
                raise ValueError("单文件超限")
            print(json.dumps({"data": base64.b64encode(data).decode("ascii")}))
        elif action == "write":
            data = sys.stdin.buffer.read(128 * 1024 + 1)
            if len(data) > 128 * 1024:
                raise ValueError("输入结果超限")
            with os.fdopen(open_file(name, True), "wb") as stream:
                stream.write(data)
            print(json.dumps({"bytes": len(data), "path": name}))
        else:
            raise ValueError("未知文件动作")
    except Exception:
        print("受控文件读取或写入失败", file=sys.stderr)
        sys.exit(2)
