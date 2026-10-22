#!/usr/bin/env python3
"""
在 PC 上复现 App 的核心链路，用来验证「读 /proc/net/tcp 拿对端地址 → 直连发送」。

用法：
  终端 A: python3 tools/e2e_test.py server 9000
  终端 B: python3 tools/e2e_test.py client 127.0.0.1 9000

client 会先用 App 里同一套逻辑扫描 /proc/net/tcp，找到指向 9000 的已建立连接，
打印出它的 uid / 对端地址（这就是 App 列表里显示的那一条），然后自己再建一条
连接把消息发过去——与 App 的行为完全一致。
"""
import os
import socket
import struct
import sys
import threading
import time

PROC_TCP = "/proc/net/tcp"
PROC_TCP6 = "/proc/net/tcp6"

STATES = {
    "01": "ESTABLISHED", "02": "SYN_SENT", "03": "SYN_RECV",
    "04": "FIN_WAIT1", "05": "FIN_WAIT2", "06": "TIME_WAIT",
    "07": "CLOSE", "08": "CLOSE_WAIT", "09": "LAST_ACK",
    "0A": "LISTEN", "0B": "CLOSING",
}


def decode_v4(hexstr):
    if len(hexstr) != 8:
        return None
    v = int(hexstr, 16)
    return ".".join(str((v >> (8 * i)) & 0xFF) for i in range(4))


def decode_v6(hexstr):
    if len(hexstr) != 32:
        return None
    raw = b"".join(struct.pack("<I", int(hexstr[i:i + 8], 16)) for i in range(0, 32, 8))
    if raw[:10] == b"\x00" * 10 and raw[10:12] == b"\xff\xff":
        return ".".join(str(b) for b in raw[12:])
    return socket.inet_ntop(socket.AF_INET6, raw)


def parse(path, ipv6=False):
    out = []
    try:
        with open(path, "r") as fh:
            lines = fh.read().splitlines()[1:]
    except OSError as exc:
        print(f"  读取 {path} 失败: {exc}")
        return out
    for line in lines:
        f = line.split()
        if len(f) < 10:
            continue
        lip, lport = f[1].rsplit(":", 1)
        rip, rport = f[2].rsplit(":", 1)
        out.append({
            "local": (decode_v6(lip) if ipv6 else decode_v4(lip), int(lport, 16)),
            "remote": (decode_v6(rip) if ipv6 else decode_v4(rip), int(rport, 16)),
            "state": STATES.get(f[3].upper(), f[3]),
            "uid": int(f[7]),
            "inode": int(f[9]),
        })
    return out


def scan(port=None):
    sessions = parse(PROC_TCP) + parse(PROC_TCP6, ipv6=True)
    if port is not None:
        sessions = [s for s in sessions if s["remote"][1] == port]
    return sessions


def run_server(port):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(8)
    print(f"[server] 监听 0.0.0.0:{port}，等待连接…")
    while True:
        conn, addr = srv.accept()
        print(f"[server] 来自 {addr[0]}:{addr[1]} 的连接")
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


def handle(conn, addr):
    with conn:
        conn.settimeout(30)
        while True:
            data = conn.recv(4096)
            if not data:
                print(f"[server] {addr} 关闭")
                return
            text = data.decode("utf-8", "replace")
            print(f"[server] 收到 {len(data)} 字节: {text.rstrip()!r}")
            conn.sendall(f"ACK:{text}".encode())


def run_client(port, target_port):
    # 1) 先建一条「老应用」的连接，保持不断
    legacy = socket.create_connection(("127.0.0.1", target_port))
    print(f"[client] 已建立模拟「老应用」连接 {legacy.getsockname()} -> {legacy.getpeername()}")
    time.sleep(0.3)

    # 2) 用 App 的逻辑扫描会话表，找出这条连接
    matches = [s for s in scan(target_port)
               if s["state"] == "ESTABLISHED" and s["local"][1] == legacy.getsockname()[1]]
    print(f"[client] 扫描到 {len(matches)} 条指向 {target_port} 的已建立会话:")
    for s in matches:
        print(f"         uid={s['uid']} inode={s['inode']} "
              f"{s['local'][0]}:{s['local'][1]} -> {s['remote'][0]}:{s['remote'][1]}")

    if not matches:
        print("[client] 没找到目标会话，可能 procfs 被限制")
        legacy.close()
        return

    picked = matches[0]
    host, rport = picked["remote"]

    # 3) 新连接直连同一个对端（App 里点「发送」就是这么做的）
    with socket.create_connection((host, rport), timeout=5) as sock:
        sock.sendall(b"hello from direct-tcp\n")
        sock.settimeout(3)
        try:
            print(f"[client] 回执: {sock.recv(4096)!r}")
        except socket.timeout:
            print("[client] 对端没有回执（正常，很多服务不回）")

    legacy.close()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    mode = sys.argv[1]
    if mode == "server":
        run_server(int(sys.argv[2]) if len(sys.argv) > 2 else 9000)
    elif mode == "client":
        # client <target_port>，兼容多写一个目标 IP 的形式
        args = [a for a in sys.argv[2:] if a not in ("127.0.0.1", "localhost")]
        run_client(0, int(args[-1]))
    elif mode == "scan":
        for s in scan():
            if s["state"] == "ESTABLISHED":
                print(f"uid={s['uid']:<6} {s['local'][0]}:{s['local'][1]} -> "
                      f"{s['remote'][0]}:{s['remote'][1]}  inode={s['inode']}")
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
