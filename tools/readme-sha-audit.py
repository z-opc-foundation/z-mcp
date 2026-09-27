#!/usr/bin/env python3
"""把 README 里被当成"某个提交存在"的十六进制引用逐枚 `git cat-file` 过, 有一枚解析不出就红.

为什么要有这一步: 台账里的提交号是**手敲**的, 而手敲的号没有任何尺会去读它。#55 那一轮我把
本轮测试那一笔记成了 2c1811b(真实是 296fa4d), 盘上两处引用一起错, 而 CI、`mvn`、四格 tally
全都在它自己那一枚正确的号上绿着 —— 错号唯一的暴露方式是有人拿 `git cat-file -t` 去问一次。

判据只筛**形状**: 反引号包住、长度恰好 7 或 40 的十六进制串, 就是"我声称这是一枚提交"的主张。
别的十六进制串一律不参与判定, 而且不是随手放过的 —— 不加长度筛的第一版尺把生产码 md5(32)、
整树聚合哈希(16)、CI run 号(11 位十进制)、job 号(12 位)全当提交号, 一次报出 122 枚"BAD";
那种红只会把尺自己的错说成 README 的错。被筛掉的枚数每次打印出来(`dropped_by_length`),
这样"收窄判据"这一步本身是可见的, 不是一句"我改了正则以消掉误报"。

三种假绿各有对应闸, 缺一条这条尺就可能只是没跑:
1. 阳性对照 —— 每次运行都先解 `HEAD`, 解不出直接 FATAL; 否则"仓库不在 git 里""git 不在 PATH"
   都会伪装成"没有需要检查的引用";
2. 空输入 FATAL —— README 为空、或一枚 sha 形状都没筛到, 判 FATAL 不判绿(0 枚的绿不是通过);
3. 解析结果必须**恰好是 commit** —— 只判 `cat-file` 不报 `fatal` 的话, 一枚恰好撞上某个 blob/tree
   的摘要串会被算成"已验为提交"。
"""
import argparse
import os
import re
import subprocess
import sys

HEX_SPAN = re.compile(r"`([0-9a-fA-F]+)`")
SHA_LENGTHS = (7, 40)


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True)


def object_type(rev):
    r = git("cat-file", "-t", rev)
    return r.stdout.strip() if r.returncode == 0 else None


def readme_text(head):
    if head:
        r = git("show", "HEAD:README.md")
        if r.returncode != 0:
            sys.exit("FATAL: 读不到 HEAD:README.md (%s)" % r.stderr.strip())
        return r.stdout, "HEAD"
    root = git("rev-parse", "--show-toplevel")
    if root.returncode != 0:
        sys.exit("FATAL: 不在 git 工作树里, 提交号无处可解(这不是'没有引用', 是尺瞎了)")
    path = os.path.join(root.stdout.strip(), "README.md")
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read(), "worktree"
    except OSError as exc:
        sys.exit("FATAL: 读不到 %s (%s)" % (path, exc))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--head", action="store_true", help="审计 HEAD 里那版 README(默认读工作树)")
    args = ap.parse_args()

    control = object_type("HEAD")
    if control != "commit":
        sys.exit("FATAL: 阳性对照失败, HEAD 解不出 commit(得到 %r) —— 红也要红得有名, 不许静默" % control)

    text, source = readme_text(args.head)
    if not text.strip():
        sys.exit("FATAL: README 是空的, 这里的一条绿什么都不说明")

    spans = {t.lower() for t in HEX_SPAN.findall(text)}
    cited = sorted(t for t in spans if len(t) in SHA_LENGTHS)
    if not cited:
        sys.exit("FATAL: %d 字符的 README 里筛出 0 枚 sha 形状引用 —— 尺看不见, 不是全绿" % len(text))

    bad = [t for t in cited if object_type(t) != "commit"]
    print("readme-sha-audit: source=%s hex_spans=%d sha_shaped=%d dropped_by_length=%d control=HEAD:commit"
          % (source, len(spans), len(cited), len(spans) - len(cited)))
    if bad:
        for t in bad:
            print("  NOT-A-COMMIT %s" % t)
        sys.exit("readme-sha-audit: FAIL (%d/%d 枚引用解不出 commit)" % (len(bad), len(cited)))
    print("readme-sha-audit: OK (%d 枚引用全部解出 commit)" % len(cited))


if __name__ == "__main__":
    main()
