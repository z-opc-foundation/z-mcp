#!/usr/bin/env python3
"""把 surefire 的 XML 报告压成一张按模块的表, 并且**只有该跑的模块都跑到**才判绿.

为什么要这么一步: Maven 默认是 fail-fast —— 第一个模块的测试一红, 它下游的模块**一条都不跑**
(`-fae` 也救不了, fail-at-end 恰恰会跳过依赖失败模块的那些)。于是 CI 的红消息里分不清
"下游也通过了"和"下游根本没测", 这个歧义实测撞到过一次: run `36237383572` 里
`z-mcp-server` 那 21 条就是这么消失的。所以 `mvn` 那一步带 `-Dmaven.test.failure.ignore=true`
把四格全跑出来, **门禁挪到这里**。

三条判红缺一不可, 每条都对应一种"看着像绿的假象":
1. 任何 failure / error ⇒ 红(直白);
2. skipped > 0 ⇒ 红 —— 本仓一条 `@Ignore`/`Assume` 都没有(实测 0 命中), 所以"跳过的"在这里
   只可能是"某个真判据没跑起来"; 把它算成通过是拿门禁换安静;
3. 该有报告的模块没报告 ⇒ 红 —— 包括整个仓库一条报告都没有的情形。**空输入必须当场判废**,
   否则"0 failures"会被打印成满分(这坑本仓踩过不止一次)。

"哪些模块该跑"不写死在脚本里, 而是从根 `pom.xml` 的 `<modules>` 现读: 加了模块忘了登记门禁,
正是这套表最安静的失效方式。有 `src/test/java` 的模块必须交出报告; 没有测试源码的模块
(如 `z-mcp-api`)整行标 "-", 模块名照打 —— 那是说得出名字的空格, 不是缺格。
"""

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ATTRS = ("tests", "failures", "errors", "skipped")


def reactor_modules(root):
    """根 pom 里的 <modules> —— 用它现算"该有哪几格", 别在脚本里抄一份清单."""
    pom = os.path.join(root, "pom.xml")
    text = open(pom, encoding="utf-8").read()
    body = re.search(r"<modules>(.*?)</modules>", text, re.S)
    if not body:
        die("根 pom 里没有 <modules>: %s" % pom)
    mods = re.findall(r"<module>\s*([^<\s]+)\s*</module>", body.group(1))
    if not mods:
        die("根 pom 的 <modules> 是空的")
    return mods


def module_has_tests(module_dir):
    src = os.path.join(module_dir, "src", "test", "java")
    if not os.path.isdir(src):
        return False
    for _dir, _sub, files in os.walk(src):
        for f in files:
            if f.endswith(".java"):
                return True
    return False


def tally_module(module_dir):
    """一个模块的 surefire 报告 → (读数, 报告份数, 具体问题列表)."""
    reports = sorted(glob.glob(os.path.join(
        module_dir, "target", "surefire-reports", "TEST-*.xml")))
    totals = dict((k, 0) for k in ATTRS)
    problems = []
    for path in reports:
        try:
            suite = ET.parse(path).getroot()
        except Exception as e:                         # 截断的报告(被 kill 的 fork 常见)也是线索
            problems.append("%s 解析不了: %s" % (os.path.basename(path), e))
            continue
        if suite.tag != "testsuite":
            problems.append("%s 根元素是 <%s>, 不是 <testsuite>"
                            % (os.path.basename(path), suite.tag))
            continue
        for k in ATTRS:
            v = suite.get(k)
            if v is None:
                problems.append("%s 缺 %s 属性" % (os.path.basename(path), k))
                continue
            totals[k] += int(v)
    if reports and totals["tests"] == 0:
        problems.append("有 %d 份报告但一条用例都没记(空跑)" % len(reports))
    return totals, len(reports), problems


def die(msg):
    print("FATAL " + msg)
    sys.exit(1)


def main(argv):
    root = os.path.abspath(argv[1]) if len(argv) > 1 else \
        os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if not os.path.isdir(root):
        die("找不到仓根: %s" % root)

    rows = []
    problems = []
    grand = dict((k, 0) for k in ATTRS)
    for mod in reactor_modules(root):
        d = os.path.join(root, mod)
        if not os.path.isdir(d):
            problems.append("%s 在 pom 的 <modules> 里但目录不存在" % mod)
            continue
        if not module_has_tests(d):
            rows.append((mod, "-", "-", "-", "-", 0))
            continue
        totals, count, own = tally_module(d)
        problems.extend("%s: %s" % (mod, p) for p in own)
        if count == 0:
            problems.append("%s 一条报告都没有 ⇒ 这个模块没被测过(不是通过)" % mod)
        rows.append((mod, str(totals["tests"]), str(totals["failures"]),
                     str(totals["errors"]), str(totals["skipped"]), count))
        for k in ATTRS:
            grand[k] += totals[k]

    measured = [r for r in rows if r[1] != "-"]
    if not measured:
        problems.append("整个 reactor 没有任何一个模块交出报告")

    print("module           tests  failures  errors  skipped  reports")
    for r in rows:
        print("%-16s %5s  %8s  %6s  %7s  %7s" % r[:6])
    print("%-16s %5d  %8d  %6d  %7d" % (("TOTAL",) + tuple(
        grand[k] for k in ATTRS)))

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("### surefire tally (jdk-by-job)\n\n")
            f.write("| module | tests | failures | errors | skipped | reports |\n")
            f.write("| --- | ---: | ---: | ---: | ---: | ---: |\n")
            for r in rows:
                f.write("| %s | %s | %s | %s | %s | %s |\n" % r[:6])
            f.write("| **total** | **%d** | **%d** | **%d** | **%d** | |\n" % tuple(
                grand[k] for k in ATTRS))

    if grand["failures"] or grand["errors"]:
        problems.append("failures=%d errors=%d" % (grand["failures"], grand["errors"]))
    if grand["skipped"]:
        problems.append("skipped=%d: 本仓没有 @Ignore/Assume, 跳过的不算通过"
                        % grand["skipped"])
    if problems:
        for p in problems:
            print("FAIL " + p)
        sys.exit(1)
    print("OK: %d 个有测试的模块全部到齐, %d 条用例, 0 failures / 0 errors / 0 skipped"
          % (len(measured), grand["tests"]))


if __name__ == "__main__":
    main(sys.argv)
