"""受控注入持久写入和删除失败，证明残留仍被计入实际容量。"""
import json
from pathlib import Path
import time
from unittest.mock import patch
from broker import Broker, Rejected

broker = Broker('/var/lib/ruoyi-ai-workspace-storage-failure-test', 'ruoyi-ai-workspace:20260921')
request = {'userId': 1, 'runId': 1, 'runCreatedAt': time.time()}
try:
    assert broker.execute({**request, 'command': "printf retained > file.txt"})['exitCode'] == 0
    with broker.lock, patch('broker.os.fsync', side_effect=OSError('受控写入故障')), patch.object(Path, 'unlink', side_effect=PermissionError('受控清理故障')):
        try:
            broker.publish({**request, 'path': 'file.txt', 'name': '故障验收.txt'})
            raise AssertionError('故障未触发')
        except Rejected:
            pass
        status = broker.status()
        assert status['artifactCount'] == 1 and status['artifactBytes'] == 8 and status['cleanupError']
        assert sum(path.stat().st_size for path in broker.files.iterdir()) == 8
    broker.cleanup()
    assert broker.status()['artifactCount'] == 0 and not list(broker.files.iterdir())
    print(json.dumps({'passed': True, 'reservedBytesDuringFailure': 8, 'afterRecoveryBytes': 0, 'image': broker.image}))
finally:
    broker.shutdown()
