#!/usr/bin/env python3
"""
解析 Android dumpsys bluetooth_manager 的 BTSNOOP_LOG_SUMMARY（小米/Android 13 实测格式）

外层格式（dumpsys 输出）：
    base64  ->  [9 字节头] + zlib 压缩数据
内层格式（解压后，每条记录）：
    [u16 orig_len][u16 incl_len][u32 距上一条记录的微秒数][data: incl_len 字节]
    （时间字段已用配对事件核对：SMP 30s 超时与 dumpsys 的 BOND_STATE_NONE 时刻吻合）
    data[0] 为类型标记：
        0x10 = HCI Event   (data[1:] = <evt_code> <plen> <params>)
        0x20 = HCI Command (data[1:] = <opcode:2LE> <plen> <params>)
        0x11 = ACL 收（背夹→手机），0x21 = ACL 发（手机→背夹）
    输出首列为距第一条记录的秒数
用法:
    python3 mi_snoop.py dump.txt            # dumpsys 保存的文本
    python3 mi_snoop.py dump.txt --att      # 只打印 ATT 层（协议逆向主要看这个）
    python3 mi_snoop.py dump.txt --handles # 打印 handle->UUID 映射
"""
import base64, zlib, struct, sys, argparse
from collections import Counter, OrderedDict

# ---- HCI Event 码 ----
EVT = {
    0x03: "ConnComplete", 0x04: "ConnRequest", 0x05: "DisconnComplete",
    0x07: "RemoteNameReqComplete", 0x08: "EncryptChange", 0x0e: "CmdComplete",
    0x0f: "CmdStatus", 0x10: "HWError", 0x13: "NumCompletedPkts",
    0x14: "ModeChange", 0x17: "LinkSupervisionTimeout", 0x1a: "DataBufOverflow",
    0x20: "ReadRemoteVerInfoComplete", 0x30: "EncryptionKeyRefreshComplete",
    0x3e: "LEMetaEvent", 0x57: "EnhancedFlushComplete", 0x58: "AuthComplete",
    0xff: "VendorSpecific",
}
# ---- LE Meta 子事件 ----
LE_SUB = {
    0x01: "LEConnComplete", 0x02: "LEAdvReport", 0x03: "LEConnUpdateComplete",
    0x04: "LEReadRemoteFeaturesComplete", 0x05: "LELongTermKeyReq",
    0x06: "LERemoteConnParamReq", 0x08: "LEEnhancedConnComplete",
    0x0d: "LEExtAdvReport", 0x12: "LEConnParamUpdateComplete",
}
# ---- ATT opcode ----
ATT = {
    0x01: "ErrorRsp", 0x02: "ExchangeMTURq", 0x03: "ExchangeMTURs",
    0x04: "FindInfoRq", 0x05: "FindInfoRs", 0x08: "ReadByTypeRq",
    0x09: "ReadByTypeRs", 0x0a: "ReadRq", 0x0b: "ReadRs",
    0x10: "ReadByGroupTypeRq", 0x11: "ReadByGroupTypeRs",
    0x12: "WriteRq", 0x13: "WriteRs", 0x16: "PrepareWriteRq",
    0x17: "PrepareWriteRs", 0x18: "ExecuteWriteRq", 0x19: "ExecuteWriteRs",
    0x1b: "Notification", 0x1d: "Indication", 0x1e: "Confirm",
    0x52: "WriteCmd", 0xd2: "SignedWriteCmd",
    0x8b: "ReadMultipleRq", 0x8c: "ReadMultipleRs",
    0x1e: "Confirm", 0x20: "ReadByTypeRq(128)", 0x21: "ReadByTypeRs(128)",
}

HANDLE_NAMES = {}
ADV_NAMES = {}


def load(path):
    """从 dumpsys 文本中提取并解压 snoop 数据"""
    raw = open(path, 'rb').read()
    if b'BEGIN:BTSNOOP_LOG_SUMMARY' in raw:
        seg = raw.split(b'BEGIN:BTSNOOP_LOG_SUMMARY', 1)[1]
        seg = seg.split(b'END:BTSNOOP_LOG_SUMMARY', 1)[0]
        seg = seg.split(b')', 1)[1] if seg.lstrip().startswith(b'(') else seg
        b64 = b''.join(seg.split())
        comp = base64.b64decode(b64)
    else:
        comp = raw
    # 外层 9 字节头 + zlib
    for off in range(0, 24):
        if comp[off:off + 2] in (b'\x78\x9c', b'\x78\x01', b'\x78\xda'):
            try:
                return zlib.decompress(comp[off:])
            except Exception:
                pass
    sys.exit("找不到 zlib 流，格式可能已变化")


def records(buf):
    off, out = 0, []
    while off + 8 <= len(buf):
        olen, ilen, delta_us = struct.unpack('<HHI', buf[off:off + 8])
        if ilen > len(buf) - off - 8:
            break
        out.append((delta_us, buf[off + 8:off + 8 + ilen]))
        off += 8 + ilen
    return out


def direction(data):
    """按类型标记判断方向：事件和 ACL 收为 RX，命令和 ACL 发为 TX"""
    t = data[0] if data else 0
    return 'RX' if t in (0x10, 0x11) else 'TX' if t in (0x20, 0x21) else '?'


def hexs(b):
    return ' '.join(f'{x:02x}' for x in b)


def parse_hci(data, tms, direction):
    """返回可打印行（可能多行）"""
    lines = []
    if not data:
        return lines
    t = data[0]
    body = data[1:]

    if t == 0x10 and body:                      # HCI Event
        code, plen = body[0], body[1]
        params = body[2:2 + plen]
        name = EVT.get(code, f'Evt0x{code:02x}')
        if code == 0x3e and params:             # LE Meta
            sub = params[0]
            name += '/' + LE_SUB.get(sub, f'Sub0x{sub:02x}')
            if sub == 0x02 and len(params) >= 12:   # LE Adv Report
                try:
                    n = params[10]
                    if 11 + n <= len(params):
                        nm = params[11:11 + n]
                        txt = nm.decode('utf-8', 'replace')
                        if txt.isprintable() and txt.strip():
                            ADV_NAMES[txt] = True
                            name += f'  name="{txt}"'
                except Exception:
                    pass
        lines.append(f"{tms:10.3f} EVT  {name:<34} {hexs(params)}")

    elif t == 0x20 and len(body) >= 3:          # HCI Command
        op, plen = struct.unpack('<HB', body[0:3])
        params = body[3:3 + plen]
        lines.append(f"{tms:10.3f} CMD  opcode=0x{op:04x} plen={plen:<3} {hexs(params)}")

    elif t in (0x11, 0x21, 0x31, 0x41):         # ACL（0x11=RX, 0x21=TX，见实测）
        acl = body
        if len(acl) < 4:
            return lines
        hf, alen = struct.unpack('<HH', acl[0:4])
        handle, pb = hf >> 12, (hf >> 8) & 0x3
        l2 = acl[4:4 + alen]
        tag = 'ACL'
        if len(l2) >= 4:
            l2len, cid = struct.unpack('<HH', l2[0:4])
            att = l2[4:4 + l2len]
            if cid == 0x0004 and att:
                op = att[0]
                nm = ATT.get(op, f'0x{op:02x}')
                # 句柄 -> UUID
                if op in (0x11, 0x05, 0x09, 0x01, 0x07) and len(att) >= 3:
                    learn_handles(op, att)
                h = ''
                if op in (0x12, 0x52, 0x1b, 0x1d, 0x0a, 0x08, 0x10) and len(att) >= 3:
                    hd = struct.unpack('<H', att[1:3])[0]
                    u = HANDLE_NAMES.get(hd)
                    h = f'  h=0x{hd:04x}' + (f'[{u}]' if u else '')
                lines.append(f"{tms:10.3f} ATT  t=0x{t:02x} {nm:<18}{h} {hexs(att)}")
                return lines
            elif cid == 0x0005:
                tag += '/LE-SM'
            elif cid == 0x0006:
                tag += '/SMP'
        lines.append(f"{tms:10.3f} {tag:<5} h={handle:<3} pb={pb} {hexs(l2)}")
    else:
        lines.append(f"{tms:10.3f} 0x{t:02x}  {hexs(body)}")
    return lines


def learn_handles(op, att):
    """从服务发现响应中学习 handle -> UUID"""
    try:
        if op == 0x11:                       # ReadByGroupTypeRs
            il = att[1]; i = 2
            while i + il <= len(att) and il >= 6:
                h = struct.unpack('<H', att[i:i + 2])[0]
                HANDLE_NAMES[h] = hexs(att[i + 4:i + il]); i += il
        elif op == 0x05:                     # FindInfoRs
            f = att[1]; i = 2
            while i + 4 <= len(att):
                h = struct.unpack('<H', att[i:i + 2])[0]
                n = 2 if f == 0x01 else 16
                if i + 2 + n <= len(att):
                    HANDLE_NAMES[h] = hexs(att[i + 2:i + 2 + n])
                i += 2 + n
        elif op == 0x09:                     # ReadByTypeRs
            il = att[1]; i = 2
            while i + il <= len(att) and il >= 5:
                h = struct.unpack('<H', att[i:i + 2])[0]
                HANDLE_NAMES[h] = hexs(att[i + 2:i + il]); i += il
        elif op == 0x07 and len(att) >= 3:   # ReadByGroupTypeRq
            h = struct.unpack('<H', att[1:3])[0]
            HANDLE_NAMES.setdefault(h, '?')
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dump')
    ap.add_argument('--att', action='store_true', help='只显示 ATT 层')
    ap.add_argument('--handles', action='store_true', help='只显示 handle->UUID')
    ap.add_argument('--adv', action='store_true', help='只显示广播名')
    ap.add_argument('--grep', help='十六进制子串过滤')
    a = ap.parse_args()

    buf = load(a.dump)
    recs = records(buf)
    print(f"# 解压后 {len(buf)} 字节, {len(recs)} 条 HCI 记录")

    t_us = -recs[0][0] if recs else 0    # 第一条的增量相对于文件头时间，不计入
    for delta_us, data in recs:
        t_us += delta_us
        for ln in parse_hci(data, t_us / 1e6, direction(data)):
            if a.att and 'ATT' not in ln:
                continue
            if a.grep and a.grep.lower() not in ln.lower():
                continue
            print(ln)

    if a.handles or a.att:
        print('\n# handle -> UUID 映射')
        for h in sorted(HANDLE_NAMES):
            print(f'#   0x{h:04x} = {HANDLE_NAMES[h]}')
    if a.adv:
        print('\n# 广播名:', ', '.join(sorted(ADV_NAMES)))


if __name__ == '__main__':
    main()
