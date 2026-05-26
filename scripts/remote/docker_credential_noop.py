"""docker-credential-noop — a no-op Docker credential helper for non-interactive
use cases (e.g. SSH sessions on Windows where docker-credential-desktop fails
with `A specified logon session does not exist`).

Implements the Docker credential helper protocol just enough to make `docker
pull` of public images succeed:

  ./docker-credential-noop list              -> {}
  ./docker-credential-noop get  (stdin URL)  -> {"Username":"","Secret":""}
  ./docker-credential-noop store/erase       -> exit 0 (silent)

Build with PyInstaller:
    pyinstaller --onefile --noconsole=False docker_credential_noop.py
       OR
    pyinstaller --onefile docker_credential_noop.py
"""
import sys


def main() -> int:
    if len(sys.argv) < 2:
        return 0
    action = sys.argv[1].strip().lower()
    if action == "list":
        sys.stdout.write("{}")
    elif action == "get":
        # consume stdin so Docker doesn't deadlock
        try:
            sys.stdin.read()
        except Exception:
            pass
        sys.stdout.write('{"Username":"","Secret":""}')
    elif action in ("store", "erase"):
        try:
            sys.stdin.read()
        except Exception:
            pass
    # anything else: silently succeed
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
