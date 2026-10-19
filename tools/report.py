#!/usr/bin/env python3
"""
BR64 抓包分析报告生成器

输入 capture_daemon.py 产出的 JSONL，输出：
  1. GATT 服务树（handle -> UUID）
  2. ATT 流量时间线（按轮询分段，可对照操作步骤）
  3. ★ 帧格式差异分析 —— 自动找出同长度命令中变化的字节位置，
     这是反推「命令码在第几字节、参数在第几字节」的关键

用法:
    python3 report.py /tmp/br64_capture.jsonl
    python3 report.py /tmp/br64_capture.jsonl --writes-only
"""
import json, sys, argparse
from collections import defaultdict

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from mi_snoop import HANDLE_NAMES


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('jsonl')
    ap.add_argument('--writes-only', action='store_true', help='只分析下行写命令')
    ap.add_argument('--from-poll', type=int, default=0, help='从第几次轮询开始')
    a = ap.parse_args()

    events = []
    with open(a.jsonl) as f:
        for ln in f:
            try:
                e = json.loads(ln)
            except Exception:
                continue
            if e['poll'] < a.from_poll:
                continue
            events.append(e)

    if not events:
        sys.exit("没有数据")

    # ---------- 1. GATT 树 ----------
    print("=" * 78)
    print("1. GATT 服务树")
    print("=" * 78)
    if HANDLE_NAMES:
        for h in sorted(HANDLE_NAMES):
            print(f"   0x{h:04x}  {HANDLE_NAMES[h]}")
    else:
        print("   （本次采集没有服务发现，可能未连接或已连接过）")

    # ---------- 2. ATT 时间线 ----------
    print()
    print("=" * 78)
    print("2. ATT 流量时间线（poll = 轮询序号，约每 3 秒一次）")
    print("=" * 78)
    by_poll = defaultdict(list)
    for e in events:
        if 'ATT' in e['line']:
            by_poll[e['poll']].append(e['line'].split(None, 2)[-1] if False else e['line'])

    for p in sorted(by_poll):
        lines = by_poll[p]
        print(f"\n--- poll {p}  ({len(lines)} 条) ---")
        for l in lines:
            print("   " + l)

    # ---------- 3. 差异分析 ----------
    print()
    print("=" * 78)
    print("3. 帧格式差异分析")
    print("=" * 78)

    writes = []   # (hex_str, bytes)
    notifs = []
    for e in events:
        l = e['line']
        if 'ATT' not in l:
            continue
        parts = l.split()
        # 形如:  idx ATT  TX  WriteCmd  h=0x..  [hex...]
        try:
            op = parts[parts.index('ATT') + 2]
        except ValueError:
            continue
        hexpart = l.split('  ', 2)[-1].strip() if '  ' in l else ''
        # 取最后一段连续 hex
        toks = l.split()
        hx = []
        started = False
        for t in toks:
            if len(t) == 2 and all(c in '0123456789abcdefABCDEF' for c in t):
                hx.append(t); started = True
            elif started:
                break
        if not hx:
            continue
        b = bytes(int(x, 16) for x in hx)
        if op in ('WriteCmd', 'WriteRq'):
            writes.append(b)
        elif op in ('Notification', 'Indication'):
            notifs.append(b)

    print(f"\n下行写命令 {len(writes)} 条，上行通知 {len(notifs)} 条\n")

    def diff_report(name, blobs, limit=40):
        if not blobs:
            print(f"[{name}] 无数据")
            return
        # 去重保序
        uniq = []
        seen = set()
        for b in blobs:
            k = b.hex()
            if k not in seen:
                seen.add(k); uniq.append(b)
        print(f"[{name}] {len(blobs)} 条，去重后 {len(uniq)} 种不同帧")
        # 按长度分组
        bylen = defaultdict(list)
        for b in uniq:
            bylen[len(b)].append(b)
        for L in sorted(bylen):
            grp = bylen[L]
            print(f"\n  ── 长度 {L} 字节，{len(grp)} 种 ──")
            # 找出每个字节位置上出现的不同值
            vary = []
            for i in range(L):
                vals = {b[i] for b in grp}
                if len(vals) > 1:
                    vary.append((i, sorted(vals)))
            print(f"     变化位置: {[i for i, _ in vary] if vary else '（无变化，全部相同）'}")
            for i, vals in vary[:12]:
                print(f"       字节[{i:2d}] 取值 {[f'0x{v:02x}' for v in vals]}")
            for b in grp[:limit]:
                print(f"       {b.hex(' ')}")

    diff_report("下行写命令（App → 背夹）", writes)
    print()
    diff_report("上行通知（背夹 → App）", notifs)

    # ---------- 4. 假设提示 ----------
    print()
    print("=" * 78)
    print("4. 下一步")
    print("=" * 78)
    print("""把上面「长度 N 字节」分组里的帧，和你的操作步骤对照：
  - 完全相同的帧  → 查询/心跳类命令
  - 只有 1 个字节不同的帧 → 那个字节就是该功能的命令码或参数
  - 多个字节不同的帧 → 按变化位置判断字段含义

确定后填写：
  heisha-lite/app/src/main/java/com/heisha/lite/ble/BleConstants.kt   (UUID/帧头)
  heisha-lite/app/src/main/java/com/heisha/lite/ble/CoolerProtocol.kt (build/parse)
""")


if __name__ == '__main__':
    main()
