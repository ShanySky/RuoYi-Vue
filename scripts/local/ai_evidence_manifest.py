"""记录配对工作树与权威文档版本，只写文件哈希，不复制凭据或运行数据。"""

from datetime import datetime
import hashlib
import json
from pathlib import Path
import subprocess

from ai_evolution_vm import EVIDENCE

BACKEND = Path(__file__).resolve().parents[2]
WORKSPACE = BACKEND.parents[2]
ROOTS = {"backend": BACKEND, "frontend": BACKEND.parent / "RuoYi-Vue3", "documents": WORKSPACE / "RuoYi-Vue"}


def git(root, *arguments):
    return subprocess.check_output(["git", *arguments], cwd=root).decode("utf-8").strip()


manifest = {"phase": "API-1", "createdAt": datetime.now().isoformat(), "repositories": {}}
for name, root in ROOTS.items():
    changed = set(git(root, "ls-files", "-z", "--modified", "--others", "--exclude-standard").split("\0")) - {""}
    files = {}
    for relative in sorted(changed):
        path = root / relative
        if not path.is_file():
            files[relative] = {"deleted": True}
            continue
        raw = path.read_bytes()
        if path.suffix in (".java", ".js", ".mjs", ".vue", ".py", ".txt", ".md", ".sql", ".sh", ".yml"):
            text = raw.decode("utf-8-sig")
            assert "\ufffd" not in text, "文本出现替换字符：" + str(path)
        files[relative] = {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
    manifest["repositories"][name] = {"path": str(root), "branch": git(root, "branch", "--show-current"),
        "head": git(root, "rev-parse", "HEAD"), "uncommittedFiles": files,
        "diffSha256": hashlib.sha256(subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=root)).hexdigest()}
path = EVIDENCE / ("stage1-versions-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"manifest": str(path), "changedFiles": {name: len(value["uncommittedFiles"])
    for name, value in manifest["repositories"].items()}}, ensure_ascii=False))
