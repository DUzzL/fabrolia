"""Block until the overnight queue needs attention: a scenario failed, two runs in a row did not
finish ok, the queue is done, or the log stopped moving for 45 minutes. Prints the reason."""
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mc  # noqa: E402

LOG = mc.HOME / 'results' / 'overnight.log'
start = int(sys.argv[1]) if len(sys.argv) > 1 else 0  # lines already handled
last_change, last_size = time.time(), -1
while True:
    lines = LOG.read_text(encoding='utf-8', errors='replace').splitlines() if LOG.exists() else []
    new = lines[start:]
    results = [l for l in new if '  -> ' in l]
    bad_streak = 0
    for l in results:
        bad_streak = 0 if l.endswith('-> ok') else bad_streak + 1
        if bad_streak >= 2:
            break
    if any('!!!' in l for l in new) or bad_streak >= 2 or any('queue done' in l for l in new):
        print('\n'.join(new[-8:]))
        print('LINES', len(lines))
        break
    if len(lines) != last_size:
        last_size, last_change = len(lines), time.time()
    elif time.time() - last_change > 2700:
        print('STALLED for 45 min; last lines:\n' + '\n'.join(lines[-4:]))
        print('LINES', len(lines))
        break
    time.sleep(60)
