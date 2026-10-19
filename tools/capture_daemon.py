#!/usr/bin/env python3
"""
BR64 背夹 BLE 持续采集器

问题：小米的 snoop 日志是 256KB 环形缓冲，操作时间长会把开头（扫描/连接）顶掉。
方案：高频轮询 dumpsys，把"每次新增的记录"按轮询时间累加到本地存储，
      即使环形缓冲翻转也不丢数据。

用法:
    python3 capture_daemon.py --serial <adb devices 里的序列号> --out /tmp/br64_capture.jsonl
    （另开终端操作手机；Ctrl-C 停止并生成摘要）

采集期间可用 tools/report.py 随时查看当前进度。
"""
import subprocess, sys, time, json, argparse, os, shutil

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mi_snoop import load, records, parse_hci, direction, HANDLE_NAMES, ADV_NAMES

ADB = shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def pull(serial: str, tmp: str) -> bytes:
    """拉一次 dumpsys 并解压成记录流"""
    with open(tmp, "wb") as f:
        subprocess.run([ADB, "-s", serial, "shell", "dumpsys", "bluetooth_manager"],
                       stdout=f, stderr=subprocess.DEVNULL, timeout=120)
    return load(tmp)


def rec_key(delta_us: int, data: bytes) -> str:
    """记录指纹，用于跨轮询去重"""
    return f"{delta_us:08x}:{data.hex()}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--interval", type=float, default=3.0, help="轮询间隔秒")
    ap.add_argument("--max-sec", type=float, default=0, help="0=一直跑")
    a = ap.parse_args()

    seen = {}          # key -> 首次见到的轮询序号
    seq = 0            # 轮询序号
    tmp = "/tmp/_snoop_poll.txt"
    fout = open(a.out, "w", buffering=1)
    t_start = time.time()

    print(f"# 采集开始 serial={a.serial} 间隔={a.interval}s   Ctrl-C 停止")
    print(f"# 时间   轮询  总记录  新增  ATT  广播名")

    try:
        while True:
            try:
                buf = pull(a.serial, tmp)
            except Exception as e:
                print(f"# 拉取失败: {e}", file=sys.stderr)
                time.sleep(a.interval); continue

            recs = records(buf)
            new_cnt = att_cnt = 0
            names_now = set()

            for delta_us, data in recs:
                k = rec_key(delta_us, data)
                if k in seen:
                    continue
                seen[k] = seq
                new_cnt += 1

                # 解析成可读行
                for ln in parse_hci(data, seq, direction(data)):
                    if "ATT" in ln:
                        att_cnt += 1
                    fout.write(json.dumps({"poll": seq, "line": ln}, ensure_ascii=False) + "\n")
                # 采集广播名
                if data and data[0] == 0x10 and len(data) > 2 and data[1] == 0x3e:
                    pass
                names_now |= set(ADV_NAMES)

            el = time.time() - t_start
            print(f"{el:7.1f}s {seq:5d} {len(recs):7d} {new_cnt:5d} {att_cnt:4d}  "
                  f"{','.join(sorted(names_now)) if names_now else '-'}")

            seq += 1
            if a.max_sec and el > a.max_sec:
                break
            time.sleep(a.interval)
    except KeyboardInterrupt:
        pass
    finally:
        fout.close()
        print(f"\n# 采集结束：共 {seq} 次轮询, {len(seen)} 条去重记录")
        print(f"# 输出: {a.out}")
        if HANDLE_NAMES:
            print("# handle -> UUID:")
            for h in sorted(HANDLE_NAMES):
                print(f"#   0x{h:04x} = {HANDLE_NAMES[h]}")
        if ADV_NAMES:
            print(f"# 广播名: {', '.join(sorted(ADV_NAMES))}")


if __name__ == "__main__":
    main()
