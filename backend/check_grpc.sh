#!/bin/sh
# Check Nacos gRPC port 9848 inside the container
echo "=== Hex port for 9848 is 0x$(printf '%X' 9848) ==="
# Parse /proc/net/tcp (state 0A = LISTEN)
awk 'NR>1 {split($2, a, ":"); port = strtonum("0x" a[2]); state = $4; if (state == "0A" && port == 9848) print "PORT 9848 IS LISTENING on " a[1]}' /proc/net/tcp
awk 'NR>1 {split($2, a, ":"); port = strtonum("0x" a[2]); state = $4; if (state == "0A" && port == 8848) print "PORT 8848 IS LISTENING on " a[1]}' /proc/net/tcp
echo "---"
echo "=== Nacos log lines mentioning gRPC/9848 ==="
grep -iE "grpc|9848|rpcServer|RpcServer|listener" /home/nacos/logs/nacos.log 2>/dev/null | tail -20
echo "---"
echo "=== Checking Java process for open ports ==="
for pid in $(ls /proc | grep -E "^[0-9]+$"); do
  if [ -r /proc/$pid/cmdline ]; then
    cmdline=$(cat /proc/$pid/cmdline 2>/dev/null | tr '\0' ' ')
    if echo "$cmdline" | grep -q nacos; then
      echo "Nacos PID: $pid"
      ls -la /proc/$pid/fd/ 2>/dev/null | grep socket | head -5
      break
    fi
  fi
done
