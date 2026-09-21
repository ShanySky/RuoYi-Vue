"""命令结束后回收任务身份的所有剩余进程，失败则由可信服务删除整容器。"""

import os
from pathlib import Path
import signal
import sys
import time


def remaining():
    found = []
    for entry in Path("/proc").iterdir():
        if entry.name.isdigit() and int(entry.name) != os.getpid():
            try:
                if entry.stat().st_uid == os.getuid():
                    # 僵尸进程等待容器 init 回收，不再拥有执行能力。
                    if (entry / "stat").read_text().split(")", 1)[1].strip().split()[0] != "Z":
                        found.append(int(entry.name))
            except (FileNotFoundError, ProcessLookupError):
                pass
    return found


deadline = time.monotonic() + 2
while time.monotonic() < deadline:
    processes = remaining()
    if not processes:
        print("TASK_PROCESSES_REAPED")
        sys.exit(0)
    for process in processes:
        try:
            os.kill(process, signal.SIGKILL)
        except ProcessLookupError:
            pass
    time.sleep(0.03)
sys.exit(2)
