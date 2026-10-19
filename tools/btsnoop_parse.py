#!/usr/bin/env python3
"""
btsnooz_hci.log 解析器 — 提取 BLE ATT 层读写流量
用法: python3 btsnoop_parse.py <btsnooz_hci.log> [--grep <hex>] [--raw]
输出按时间顺序的 ATT 操作，聚焦 Write Command / Notification（协议逆向最关心的两类）
"""
import struct, sys, argparse

# ---- ATT opcode 表 ----
ATT = {
    0x01: "ErrorRsp", 0x02: "ExchangeMTURq", 0x03: "ExchangeMTURs",
    0x04: "FindInfoRq", 0x05: "FindInfoRs", 0x08: "ReadByTypeRq",
    0x09: "ReadByTypeRs", 0x0A: "ReadRq", 0x0B: "ReadRs",
    0x10: "ReadByGroupTypeRq", 0x11: "ReadByGroupTypeRs",
    0x12: "WriteRq", 0x13: "WriteRs", 0x16: "PrepareWriteRq",
    0x17: "PrepareWriteRs", 0x18: "ExecuteWriteRq", 0x19: "ExecuteWriteRs",
    0x1B: "Notification", 0x1D: "Indication", 0x1E: "Confirm",
    0x52: "WriteCmd", 0xD2: "SignedWriteCmd",
    0x8B: "ReadMultipleRq", 0x8C: "ReadMultipleRs",
}
HANDLE_NAMES = {}   # handle -> UUID，由服务发现阶段填充


def parse_btsnoop(path):
    with open(path, 'rb') as f:
        data = f.read()
    if data[:8] != b'btsnoop\x00':
        sys.exit("不是合法的 btsnoop 文件（magic 不匹配）")
    version, datalink = struct.unpack('>II', data[8:16])
    off = 16
    recs = []
    while off + 24 <= len(data):
        olen, ilen, flags, drops = struct.unpack('>IIII', data[off:off+16])
        ts = struct.unpack('>q', data[off+16:off+24])[0]
        off += 24
        pkt = data[off:off+ilen]
        off += ilen
        recs.append((ts, flags, pkt))
    return version, datalink, recs


def att_records(flags, pkt):
    """从 HCI 包中取出 (handle, att_bytes)。返回 None 表示非 ACL/ATT。"""
    # datalink 1002/1006: flags bit0 = 0 主机发出, 1 控制器收到
    # 数据第一个字节是 H4 包类型（多数 Android 导出为 1002/H5 或 1001/H4）
    if not pkt:
        return None
    ptype = pkt[0]
    body = pkt[1:]
    if ptype != 0x02:            # 只关心 ACL 数据
        return None
    # ACL 头: handle(12b)+flags(4b) + len(16b)
    if len(body) < 4:
        return None
    hf = struct.unpack('<H', body[0:2])[0]
    handle = hf >> 12
    acl_len = struct.unpack('<H', body[2:4])[0]
    l2 = body[4:4+acl_len]
    if len(l2) < 4:
        return None
    # L2CAP: len(16b) + cid(16b)
    l2_len, cid = struct.unpack('<HH', l2[0:4])
    if cid != 0x0004:            # 只关心 ATT
        return None
    return handle, l2[4:4+l2_len]


def hci_cmd_events(pkt):
    """提取 HCI 命令/事件（LE 扫描、连接建立等）"""
    if not pkt:
        return None
    ptype = pkt[0]
    body = pkt[1:]
    if ptype == 0x01 and len(body) >= 3:      # Command
        op = struct.unpack('<H', body[0:2])[0]
        plen = body[2]
        return ("CMD", op, body[3:3+plen])
    if ptype == 0x04 and len(body) >= 2:      # Event
        code = body[0]
        plen = body[1]
        return ("EVT", code, body[2:2+plen])
    return None


def fmt(b):
    return ' '.join(f'{x:02x}' for x in b)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('log')
    ap.add_argument('--grep', help='只显示含该 hex 子串的记录')
    ap.add_argument('--raw', action='store_true', help='也打印 HCI 命令/事件')
    ap.add_argument('--handles', action='store_true', help='只打印 handle->UUID 映射')
    a = ap.parse_args()

    ver, dl, recs = parse_btsnoop(a.log)
    print(f"# btsnoop version={ver} datalink={dl} records={len(recs)}")
    print(f"# {'时间(ms)':>10}  {'方向':<4} {'handle':>6}  {'类型':<16} 数据")
    print('-' * 100)

    t0 = recs[0][0] if recs else 0
    for ts, flags, pkt in recs:
        tms = (ts - t0) / 1000.0
        direction = 'TX' if (flags & 0x01) == 0 else 'RX'

        r = att_records(flags, pkt)
        if r:
            handle, att = r
            if not att:
                continue
            op = att[0]
            name = ATT.get(op, f'0x{op:02x}')

            # 服务发现：ReadByGroupType / FindInfo 响应里含 handle-UUID 对
            if op in (0x11, 0x05, 0x09) and len(att) >= 6:
                try:
                    if op == 0x11:   # ReadByGroupTypeRsp: [op][len][h1][g1][uuid...]
                        item_len = att[1]
                        i = 2
                        while i + item_len <= len(att) and item_len >= 6:
                            h = struct.unpack('<H', att[i:i+2])[0]
                            g = struct.unpack('<H', att[i+2:i+4])[0]
                            u = att[i+4:i+item_len]
                            HANDLE_NAMES[h] = fmt(u)
                            i += item_len
                    elif op == 0x05:  # FindInfoRsp: [op][fmt][h][uuid]...
                        fmtc = att[1]
                        i = 2
                        while i + 4 <= len(att):
                            h = struct.unpack('<H', att[i:i+2])[0]
                            if fmtc == 0x01:
                                u = att[i+2:i+4]; i += 4
                            else:
                                u = att[i+2:i+18]; i += 18
                            HANDLE_NAMES[h] = fmt(u)
                except Exception:
                    pass

            line = f"{tms:10.1f}  {direction:<4} {handle:6d}  {name:<16} {fmt(att)}"
            if a.grep and a.grep.lower() not in line.lower():
                continue
            if a.handles:
                continue
            print(line)
            continue

        if a.raw:
            he = hci_cmd_events(pkt)
            if he:
                kind, code, pl = he
                line = f"{tms:10.1f}  {direction:<4}   HCI  {kind}:0x{code:02x}          {fmt(pl)}"
                if a.grep and a.grep.lower() not in line.lower():
                    continue
                print(line)

    if HANDLE_NAMES:
        print('\n# handle -> UUID 映射（服务发现结果）')
        for h in sorted(HANDLE_NAMES):
            print(f"#   {h:5d} (0x{h:04x}) = {HANDLE_NAMES[h]}")


if __name__ == '__main__':
    main()
