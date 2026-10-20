#!/usr/bin/env python3
"""Celular falso: fala o protocolo PhoneSteam (docs/PROTOCOL.md) com um host.

Uso: fake_phone.py [host] — conecta, manda HELLO + poses, valida CONFIG/VIDEO e a lista de apps.
"""
import socket
import struct
import sys
import threading
import time

MAGIC = 0x4D545350
HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"


def msg(t, payload=b""):
    return struct.pack("<IBBHI", MAGIC, t, 0, 0, len(payload)) + payload


def read_msg(s):
    hdr = recv_all(s, 12)
    magic, t, _, _, length = struct.unpack("<IBBHI", hdr)
    assert magic == MAGIC, hex(magic)
    return t, recv_all(s, length)


def recv_all(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise EOFError
        b += c
    return b


def controller(trigger):
    return struct.pack("<BBH3f4fI4f", 3, 1, 0, 0.2, 1.2, -0.3, 0, 0, 0, 1, 1 << 2, trigger, 0, 0, 0)


def pose_body(seq):
    return struct.pack("<IQ4f3f", seq, int(time.time() * 1e6), 0, 0.3826834, 0, 0.9238795, 0, 1.65, 0) \
        + controller(0.0) + controller(0.75)


def connect(retries=50):
    for _ in range(retries):
        try:
            return socket.create_connection((HOST, 9945))
        except ConnectionRefusedError:
            time.sleep(0.1)
    raise SystemExit("host não respondeu")


# 1) conexão de controle: lista de apps
c = connect()
c.sendall(msg(2, struct.pack("<HHBBHf4f", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) + msg(5))
t, p = read_msg(c)
assert t == 6, t
print("APP_LIST", p.decode())
c.sendall(msg(7, b"steam.app.620980"))
c.close()

# 2) conexão de streaming
s = connect()
hello = struct.pack("<HHBBHf4f", 320, 240, 30, 1, 8, 0.064, 1.1, 0.9, 1.1, 1.1)
assert len(hello) == 28
s.sendall(msg(2, hello))
t, p = read_msg(s)
assert t == 3, t
print("CONFIG", struct.unpack("<HHBBH", p))

udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
running = True


def send_poses():
    seq = 0
    while running:
        body = pose_body(seq)
        assert len(body) == 144
        udp.sendto(struct.pack("<IBBH", MAGIC, 1, 1, 0) + body, (HOST, 9944))
        seq += 1
        time.sleep(0.011)


threading.Thread(target=send_poses, daemon=True).start()

frames = keyframes = 0
first_nal_types = None
while frames < 40:
    t, p = read_msg(s)
    if t != 4:
        continue
    idx, qx, qy, qz, qw, key = struct.unpack("<I4fB3x", p[:24])
    data = p[24:]
    assert data[:4] == b"\x00\x00\x00\x01" or data[:3] == b"\x00\x00\x01", data[:8]
    if key:
        keyframes += 1
        types = []
        i = 0
        while True:
            i = data.find(b"\x00\x00\x01", i)
            if i < 0:
                break
            types.append(data[i + 3] & 0x1F)
            i += 3
        first_nal_types = first_nal_types or types
    frames += 1
running = False
print(f"VIDEO frames={frames} keyframes={keyframes} first_keyframe_nals={first_nal_types}")
assert keyframes >= 1 and 7 in first_nal_types and 8 in first_nal_types and 5 in first_nal_types
s.sendall(msg(9))  # REQUEST_IDR
print("OK")
