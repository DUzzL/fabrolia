"""Host-specific stand settings, read from the environment (stand.sh exports them).

FABROLIA_STAND_JAVA         java binary of a JDK 25 with JVMCI (default: `java` on PATH)
FABROLIA_STAND_SERVER_CPUS  taskset list for the server JVM, e.g. 0-18 (default: ~60% of the CPUs)
FABROLIA_STAND_CLIENT_CPUS  taskset list for the protocol clients (default: the remaining CPUs)
Set both CPU lists to an empty string to disable pinning.
"""
import os
import shutil
import subprocess
from pathlib import Path


def _default_split():
    n = os.cpu_count() or 1
    if n < 4:
        return '', ''
    server = max(2, n * 19 // 32)
    return f'0-{server - 1}', f'{server}-{n - 1}'


def _count(cpus):
    total = 0
    for part in filter(None, cpus.split(',')):
        lo, _, hi = part.partition('-')
        total += int(hi or lo) - int(lo) + 1
    return total


_server, _client = _default_split()
SERVER_CPUS = os.environ.get('FABROLIA_STAND_SERVER_CPUS', _server)
CLIENT_CPUS = os.environ.get('FABROLIA_STAND_CLIENT_CPUS', _client)
SERVER_CPU_COUNT = _count(SERVER_CPUS)


def java():
    found = os.environ.get('FABROLIA_STAND_JAVA') or shutil.which('java')
    if not found:
        raise RuntimeError('No java: set FABROLIA_STAND_JAVA to a JDK 25 java binary')
    return Path(found).resolve()


def pinned(cpus, argv):
    """argv prefixed with taskset when a CPU list is configured."""
    return ['taskset', '-c', cpus, *argv] if cpus else list(argv)


def pin_process(cpus, pid):
    """Pin every thread of a running process (no-op without a CPU list)."""
    if cpus:
        subprocess.run(['taskset', '-a', '-p', '-c', cpus, str(pid)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
