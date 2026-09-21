"""仅在专用 Linux 验证机运行：真实成果配额、到期读取租约和低水位反例。"""

import argparse
import json
from pathlib import Path
import shutil
import threading
import time
from broker import Broker, Rejected, MIB


def run(root, image):
    expected = Path("/var/lib/ruoyi-ai-workspace-storage-test")
    assert Path(root).resolve() == expected
    broker = Broker(root, image)
    artifacts = []
    current = {}
    stop = threading.Event()
    records = []
    sequence = 10000

    def heartbeat():
        while not stop.wait(1):
            broker.lease({"tasks": list(current.values())})
    threading.Thread(target=heartbeat, daemon=True).start()

    def task(user, size):
        nonlocal sequence
        sequence += 1
        value = {"userId": user, "runId": sequence, "runCreatedAt": time.time()}
        current[sequence] = value
        output = broker.execute({**value, "command": f"python -c \"open('file.bin','wb').write(b'x'*{size})\""})
        assert output["exitCode"] == 0, output
        return value

    def close(value):
        assert broker.stop(value["userId"], value["runId"])["stopped"]
        current.pop(value["runId"], None)

    def publish(value):
        result = broker.publish({**value, "path": "file.bin", "name": "测试成果.bin"})
        artifacts.append({**value, "id": result["id"]})
        return artifacts[-1]

    def denied(value):
        try:
            publish(value)
            raise AssertionError("配额没有拒绝实际成果复制")
        except Rejected as error:
            assert "上限" in str(error), str(error)

    def clear():
        for value in list(current.values()):
            close(value)
        for value in artifacts:
            broker.delete(value)
        artifacts.clear()
        assert broker.status()["artifactCount"] == 0
        assert not list(broker.files.iterdir())

    def note(name, **facts):
        records.append({"name": name, **facts})
        print(json.dumps(records[-1], ensure_ascii=False), flush=True)

    try:
        for level, users, tasks, files in (("任务", 1, 1, 8), ("用户", 1, 4, 8), ("全局", 4, 4, 8)):
            last = None
            for user in range(1, users + 1):
                for _ in range(tasks):
                    last = task(user, 1)
                    for _ in range(files):
                        publish(last)
                    if level != "任务":
                        close(last)
            probe = last if level == "任务" else task(1 if level == "用户" else users + 1, 1)
            denied(probe)
            status = broker.status()
            assert len(list(broker.files.iterdir())) == users * tasks * files
            note(level + "成果文件数量上限在实际发布时拒绝", count=status["artifactCount"])
            clear()

        for level, users, tasks in (("任务", 1, 1), ("用户", 1, 4), ("全局", 4, 4)):
            last = None
            for user in range(1, users + 1):
                for _ in range(tasks):
                    last = task(user, 8 * MIB)
                    publish(last)
                    publish(last)
                    if level != "任务":
                        close(last)
            if level == "任务":
                assert broker.execute({**last, "command": "printf x > file.bin"})["exitCode"] == 0
                probe = last
            else:
                probe = task(1 if level == "用户" else users + 1, 1)
            denied(probe)
            actual = sum(path.stat().st_size for path in broker.files.iterdir())
            assert actual == users * tasks * 16 * MIB
            note(level + "成果字节上限在实际复制前拒绝", bytes=actual)
            clear()

        value = task(1, 32)
        artifact = publish(value)
        with broker.read(artifact) as data:
            assert data == b'x' * 32
            with broker.lock:
                broker.db.execute("update artifact set expires=? where id=?", (time.time() - 1, artifact["id"]))
                broker.db.commit()
            broker.cleanup()
            assert (broker.files / artifact["id"]).exists(), "清理误删正在读取的成果"
            try:
                with broker.read(artifact):
                    raise AssertionError("到期成果开启了新的读取")
            except Rejected:
                pass
        broker.cleanup()
        assert not (broker.files / artifact["id"]).exists()
        note("到期拒绝新读取，现有读取租约释放后实际清理文件")
        clear()
        prior = broker.disk_floor
        actual_free = shutil.disk_usage(broker.root).free
        broker.disk_floor = actual_free + 1024**3
        try:
            try:
                task(1, 1)
                raise AssertionError("低水位未拒绝实际任务创建")
            except Rejected as error:
                assert "水位" in str(error)
            assert not broker.tasks
            broker.cleanup()
        finally:
            broker.disk_floor = prior
        note("实际磁盘可用量低于配置水位时拒绝新任务，清理仍可运行", freeBytes=actual_free)
    finally:
        clear()
        stop.set()
        broker.shutdown()
        (broker.root / "evidence.json").write_text(json.dumps({"passed": len(records) == 8, "image": broker.image, "checks": records}, ensure_ascii=False, indent=2))
    assert len(records) == 8
    print("AI_WORKSPACE_STORAGE_OK", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default="/var/lib/ruoyi-ai-workspace-storage-test")
    parser.add_argument("--image", default="ruoyi-ai-workspace:20260921")
    args = parser.parse_args()
    run(args.root, args.image)
