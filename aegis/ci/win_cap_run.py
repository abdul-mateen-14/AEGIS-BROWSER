#!/usr/bin/env python3
"""Windows cap-stop supervisor (replaces msys `timeout -s INT` for ninja).

WHY (the #52 spin, diagnosed 2026-11-18): msys coreutils timeout on a NATIVE
windows child (ninja.exe) cannot deliver a POSIX SIGINT - kill() falls back
to TerminateProcess, i.e. a HARD kill. ninja buffers .ninja_log/.ninja_deps
in memory and writes them ONLY at graceful exit, so every cap-stop banked a
tree whose build log was empty relative to the outputs it shipped with. The
next run then saw "ninja explain: command line not found in log" for gen
edges, cascaded through harddeps into the entire 58.5k-edge graph, rebuilt
[1/58567] from a restored bank, hit the cap again, and repeated forever - the
windows lane banked ~7 GB per run without ever accumulating progress.

CTRL_BREAK_EVENT to a CREATE_NEW_PROCESS_GROUP process group is the real
Windows interrupt: ninja's console ctrl handler stops scheduling, lets the
running edges finish, and flushes both logs on exit - the bank stays
trustworthy and the next run resumes hot (proven behavior on the linux lane,
where `timeout -s INT` delivers a real SIGINT).

Exit codes keep the workflow's cap/error classification intact:
  - ninja finished inside the budget -> its own rc (0 = done)
  - cap: CTRL_BREAK -> graceful ninja exit (rc 0 or 1); the workflow reads
    DUR >= BUDGET-45 and marks BUILD_REASON=cap, with the installer-file
    check guarding the success path
  - hung past 150 s after CTRL_BREAK -> hard kill, rc 0 (cap by duration)
"""
import signal
import subprocess
import sys
import time


def main() -> int:
    budget = int(sys.argv[1])
    cmd = sys.argv[2:]
    p = subprocess.Popen(cmd, creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    deadline = time.time() + budget
    while True:
        rc = p.poll()
        if rc is not None:
            return rc if rc > 0 else 0
        if time.time() >= deadline:
            p.send_signal(signal.CTRL_BREAK_EVENT)
            try:
                return p.wait(timeout=150)
            except subprocess.TimeoutExpired:
                p.kill()
                p.wait()
                return 0
        time.sleep(5)


if __name__ == "__main__":
    sys.exit(main())
