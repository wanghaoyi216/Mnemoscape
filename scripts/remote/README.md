# Mnemoscape — Remote middleware deployment kit

> Local laptop (M:) hosts the dev frontend + Java microservices.
> Workpc (Tailscale `100.66.166.46`) hosts every middleware container.
> Data lives on **D:\MnemoscapeInfra**.  The C: drive on workpc is off-limits.

---

## TL;DR — one command from the laptop

```powershell
# from M:\Study\ProjectTest\Mnemoscape
powershell -File scripts\remote\Deploy-To-Workpc.ps1 -Pull -OpenFirewall
```

That uploads every artifact in this folder to `D:\MnemoscapeInfra` on workpc,
brings the stack up, waits for healthchecks, and (optionally) opens Windows
firewall.  Re-run with `-Stop` or `-Status` for the lifecycle operations.

---

## Files in this folder

| File | Where it runs | Purpose |
| --- | --- | --- |
| `Deploy-To-Workpc.ps1`          | **laptop** | scp → ssh orchestrator |
| `docker-compose.remote.yml`     | workpc     | infra-only compose, all bind mounts on D: |
| `Deploy-Remote.ps1`             | workpc     | applied by the orchestrator |
| `Stop-Remote.ps1`               | workpc     | `down` + optional `-Wipe` |
| `Status-Remote.ps1`             | workpc     | health, ports, D: usage |
| `Open-Firewall.ps1`             | workpc     | `Allow Inbound TCP` rules (run as admin) |
| `docker_credential_noop.py` + `docker-credential-noop.exe` | workpc | bypass `docker-credential-desktop` over SSH |
| `Use-LocalDev-WorkpcInfra.ps1`  | **laptop** | source `backend/.env.workpc` into the current shell |

---

## Topology

```
┌─────────────────────────────────────────────────────────────────┐
│  Dev laptop  (M:\Study\ProjectTest\Mnemoscape)                  │
│                                                                 │
│  • Vue 3 frontend (vite, npm run dev)        → http://:5173     │
│  • api-gateway     (Spring Cloud Gateway)    → http://:8080     │
│  • auth-service    (Spring Boot, MVC)        → http://:8081     │
│  • memory-service  (Spring Boot, MVC)        → http://:8082     │
│  • ai-service      (Spring Boot, MVC)        → http://:8083     │
│  • resonance-service (Spring Boot, WS)       → http://:8084     │
│  • asset-service   (Spring Boot, MVC)        → http://:8085     │
└─────────────────────────────┬───────────────────────────────────┘
                              │ Tailscale (100.66.166.46)
┌─────────────────────────────┴───────────────────────────────────┐
│  Workpc — DESKTOP-MJGHIQ6   (D:\MnemoscapeInfra)                │
│                                                                 │
│  • mysql              → :3306    (root / root123)               │
│  • redis              → :6379                                   │
│  • nacos              → :8848  + :9848                          │
│  • rabbitmq           → :5672  + :15672 mgmt UI (guest / guest) │
│  • minio              → :9000  + :9001 console                  │
│  • neo4j              → :7474  + :7687                          │
│  • etcd (internal for Milvus only)                              │
│  • milvus             → :19530 + :9091                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Recurring chores

```powershell
# Update images + restart with fresh containers
powershell -File scripts\remote\Deploy-To-Workpc.ps1 -Pull -Recreate

# Just check what's running
powershell -File scripts\remote\Deploy-To-Workpc.ps1 -Status

# Stop everything (keep data)
powershell -File scripts\remote\Deploy-To-Workpc.ps1 -Stop

# Stop + wipe D:\MnemoscapeInfra data (asks for confirmation)
ssh workpc 'powershell -File D:\MnemoscapeInfra\Stop-Remote.ps1 -Wipe'

# Open firewall (must be elevated on workpc — see note below)
ssh workpc 'powershell -File D:\MnemoscapeInfra\Open-Firewall.ps1'
```

> **About `Open-Firewall.ps1`** — Windows requires admin to create firewall
> rules.  An SSH session typically isn't elevated, so the script bails with a
> clear message.  RDP into workpc once, open an Administrator PowerShell, and
> run `D:\MnemoscapeInfra\Open-Firewall.ps1` — that's a one-shot setup.

---

## After deployment, run services locally

```powershell
cd M:\Study\ProjectTest\Mnemoscape
# load workpc endpoints into env
. .\scripts\remote\Use-LocalDev-WorkpcInfra.ps1

# start backend services one tab at a time
cd backend
.\mvnw -pl api-gateway       spring-boot:run
.\mvnw -pl auth-service      spring-boot:run
.\mvnw -pl memory-service    spring-boot:run
.\mvnw -pl ai-service        spring-boot:run
.\mvnw -pl resonance-service spring-boot:run
.\mvnw -pl asset-service     spring-boot:run

# in another tab — frontend
cd ..\frontend
npm install
npm run dev
```

---

## The Docker-credential-desktop workaround

Windows OpenSSH non-interactive sessions can't reach the user's logon token,
so `docker-credential-desktop.exe` fails with `A specified logon session does
not exist` on every `docker pull`.  Deploy-Remote handles this by:

1. Setting `~/.docker/config.json` → `"credsStore": "noop"`
2. Installing **docker-credential-noop.exe** into the user's PATH
   (`%USERPROFILE%\bin`)
3. That helper just answers `{}` to every `list` query — Docker proceeds with
   anonymous pulls, which is what you want for public registry images.

The helper sources live in `docker_credential_noop.py`; the .exe is built with
PyInstaller and shipped alongside.
