"""原样复用已提交的持续集成模型桩，只调整本地监听端口。"""

import argparse
from pathlib import Path
import subprocess
import textwrap

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--port", type=int, default=18092)
parser.add_argument("--revision", default="8c820632147edd6f18990cc07a2d2aab72036e17")
parser.add_argument("--current", action="store_true")
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
workflow = (root / ".github/workflows/ai-agent-fullstack-e2e.yml").read_text(encoding="utf-8") if args.current else \
    subprocess.check_output(["git", "show", f"{args.revision}:.github/workflows/ai-agent-fullstack-e2e.yml"],
                            cwd=root).decode("utf-8")
start = workflow.index("          import json", workflow.index("cat > /tmp/mock_openai.py"))
end = workflow.index("          PY", start)
source = textwrap.dedent(workflow[start:end])
binding = "ThreadingHTTPServer(('127.0.0.1', 18080), Handler).serve_forever()"
if source.count(binding) != 1:
    raise RuntimeError("持续集成模型桩入口已变化，须重新核查")
source = source.replace(binding, f"ThreadingHTTPServer(('127.0.0.1', {args.port}), Handler).serve_forever()")
exec(compile(source, "<repository-baseline-mock>", "exec"), {"__name__": "__main__"})
