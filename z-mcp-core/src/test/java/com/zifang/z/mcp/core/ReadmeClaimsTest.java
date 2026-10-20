package com.zifang.z.mcp.core;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * z-mcp README 定量主张自守卫 —— README 里写死的版本、模块清单、端口、配置默认值、
 * 端点集合、内容块类型、内置工具名、部署面，必须能从源码重算出来；
 * README 改了就红，代码没动就不红。
 *
 * <p><b>为什么要有这一层</b>：本仓 README 把「当前版本 0.2.1」「五模块」「默认端口
 * 18095/18096/18097」「{@code z.mcp.enabled} 默认 false」「{@code z.mcp.*} 下 5 个嵌套组」
 * 「ContentBlock 五支 + raw」「内置四件工具」「9 个 HTTP 端点」「仓根没有 Dockerfile」
 * 写成了硬数字 —— 此前「测试」那一节还写着「404 条用例 / 31 个测试类」，
 * 那是**自指计数**（守卫自己会加一个测试类进去），实测已是 429 / 34。
 * 没有任何尺子去问代码「现在还是吗」，这些数字的保质期就只剩上一次编辑 README 的那一刻。</p>
 *
 * <p><b>口径四条（都是踩过的坑）</b>：</p>
 * <ol>
 *   <li><b>事实对象只装源码侧事实</b>（{@link Facts}）—— README 侧的数值一律在核对
 *       <b>当下</b>从被核对的那份 README 里现解析。两边都从同一份 README 取数，
 *       尺子就成了恒等式比较，README 改成 18096 尺子照样绿；</li>
 *   <li><b>主张抠不到就红</b>：正则没匹配上不许静默跳过，那正是空跑；</li>
 *   <li><b>按小节名 / 表格字段定位，不按数字定位</b>：短数字是长数字的子串
 *       （「4 个」⊂「14 个」），按数字定位会匹配到别人的行上；</li>
 *   <li><b>README 标题带 emoji</b>：定位小节要剥掉 emoji 后<b>标题文字全等匹配</b>，
 *       不能用 {@code contains}（「模块结构」会误命中「模块结构与约定」）。</li>
 * </ol>
 *
 * <p>数值不等 = 红，且把<b>重算值</b>打进消息（红了要知道真数是多少，否则只是又一处猜）。
 * 核对结果<b>一次全报</b>（收集齐所有不一致再抛），撞停会把尺子退化成「一次只说一句」。</p>
 *
 * <p>本仓既有测试是 JUnit 4（经 {@code junit-vintage-engine} 运行），本守卫沿用同一口径 ——
 * 混用引擎时静默不跑比红更坏。</p>
 */
public class ReadmeClaimsTest {

    /** 仓根标志：artifactId 命中 + packaging=pom + 真带 <module>（子模块 pom 的 parent 里也有同名 artifactId）。 */
    private static final String ROOT_ARTIFACT_ID = "z-mcp";

    /** 源码侧路径一律相对仓根写死，不按目录名统计（多模块仓里同名目录不止一处）。 */
    private static final String CORE_MAIN = "z-mcp-core/src/main/java";
    private static final String ADMIN_MAIN = "z-mcp-admin/src/main/java";
    private static final String SERVER_RES = "z-mcp-server/src/main/resources";
    private static final String MCP_PROPERTIES = "z-mcp-core/src/main/java/com/zifang/z/mcp/core/properties/McpProperties.java";
    private static final String CONTENT_BLOCK = "z-mcp-api/src/main/java/com/zifang/z/mcp/api/dto/ContentBlock.java";
    private static final String BUILTIN_TOOLS = "z-mcp-core/src/main/java/com/zifang/z/mcp/core/builtin/BuiltinTools.java";
    private static final String JSON_RPC_CONTROLLER = "z-mcp-core/src/main/java/com/zifang/z/mcp/core/controller/JsonRpcController.java";
    private static final String ADMIN_CONTROLLER = "z-mcp-admin/src/main/java/com/zifang/z/mcp/admin/controller/AdminController.java";
    private static final String MCP_SCHEMA = "z-mcp-core/src/main/java/com/zifang/z/mcp/core/protocol/McpSchema.java";
    private static final String ROOT_POM = "pom.xml";

    /** HTTP 动词 —— 能力清单里也会出现（`POST` / `GET` / `DELETE`），它们不是类名。 */
    private static final Set<String> HTTP_VERBS = new LinkedHashSet<>(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));

    // ==================================================================
    // 基础设施
    // ==================================================================

    /**
     * 定位仓根：从 user.dir 向上找<b>聚合根</b>的 pom.xml。
     *
     * <p>判据要同时满足三条：<b>项目自身</b>的 artifactId 命中（用 XML 解析取顶层
     * {@code <artifactId>}，不是全文 contains —— 子模块 pom 的 {@code <parent>} 里
     * 就写着 {@code z-mcp}，全文匹配会把子模块误判成仓根）+
     * {@code <packaging>pom</packaging>} + 至少一个 {@code <module>}。</p>
     */
    private static Path repoRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 10 && p != null; i++, p = p.getParent()) {
            Path pom = p.resolve(ROOT_POM);
            if (Files.isRegularFile(pom)) {
                String t = stripXmlComments(readText(pom));
                boolean isRoot = ownArtifactId(t).equals(ROOT_ARTIFACT_ID)
                        && t.contains("<packaging>pom</packaging>")
                        && Pattern.compile("<module>").matcher(t).find();
                if (isRoot) {
                    return p;
                }
            }
        }
        throw new IllegalStateException("未找到 z-mcp 仓根：从 " + Paths.get("").toAbsolutePath()
                + " 向上找不到同时满足 artifactId=" + ROOT_ARTIFACT_ID + " / packaging=pom / 带 <module> 的 pom.xml");
    }

    /** 取 pom 里<b>项目自身</b>的 artifactId（顶层元素，不是 parent 里的那个）。 */
    private static String ownArtifactId(String pomText) {
        Document doc = parseXml(pomText);
        NodeList kids = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "artifactId".equals(n.getNodeName())) {
                return n.getTextContent().trim();
            }
        }
        return "";
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("XML 解析失败", e);
        }
    }

    private static String readText(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读文件失败：" + p, e);
        }
    }

    /** 剥掉 XML 注释（根 pom 里注释掉的 {@code <module>} 会骗过正则）。 */
    private static String stripXmlComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * 剥掉 Java 注释（块注释 + 行注释），字符串字面量原样保留。
     *
     * <p>判「类级注解」还是「方法级注解」时必须先剥注释：Javadoc 里出现过
     * {@code @RequestMapping} 字样就会被当成真映射。</p>
     */
    private static String stripComments(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                sb.append(c);
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    sb.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        sb.append(s.charAt(i));
                        i++;
                        continue;
                    }
                    if (d == quote) {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ==================================================================
    // README 侧解析
    // ==================================================================

    private static int headingLevel(String heading) {
        int n = 0;
        while (n < heading.length() && heading.charAt(n) == '#') {
            n++;
        }
        return n;
    }

    /**
     * 标题的「基名」：去掉行首 #、emoji，再去掉<b>尾部</b>的括号补充说明。
     *
     * <p>emoji 用码点判断（不吃编译编码的亏）：U+1F300–U+1FAFF、U+2600–U+27BF、
     * 变体选择符 U+FE0F、零宽连接符 U+200D。</p>
     *
     * <p>尾部括号（`（…）` / `(…)`）当成<b>装饰</b>剥掉，剩下的基名仍然<b>全等</b>匹配 ——
     * 于是「模块结构」与「模块结构与约定」照样分得开，又能定位到
     * `## 🎯 能力清单（逐条对应到代码）` 这种带补充说明的标题。
     * （z-skill 首版守卫就栽在这儿：整段标题文字全等 ⇒ 永远定位不到小节。）</p>
     */
    private static String headingText(String raw) {
        String t = raw.trim();
        while (t.startsWith("#")) {
            t = t.substring(1);
        }
        t = t.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            int cp = t.codePointAt(i);
            if (cp == 0xFE0F || cp == 0x200D
                    || (cp >= 0x1F300 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF)) {
                if (cp > 0xFFFF) {
                    i++;
                }
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return stripTrailingParen(sb.toString().trim());
    }

    /** 去掉标题文字<b>尾部</b>的括号补充说明（`能力清单（逐条对应到代码）` → `能力清单`）。 */
    private static String stripTrailingParen(String title) {
        String t = title.trim();
        while (t.endsWith("）") || t.endsWith(")")) {
            int open = t.endsWith("）") ? t.lastIndexOf('（') : t.lastIndexOf('(');
            if (open < 0) {
                break;
            }
            t = t.substring(0, open).trim();
        }
        return t;
    }

    /**
     * 取某个标题的小节正文（到下一个「同级或更高级」标题为止，{@code ###} 子标题仍属本节）。
     *
     * <p>围栏代码块内以 # 开头的行（项目结构树）不算标题，必须跳过。</p>
     */
    private static String section(String readme, String headingText) {
        String[] lines = readme.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inFence = false;
        boolean started = false;
        int level = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence;
                if (started) {
                    sb.append(line).append('\n');
                }
                continue;
            }
            if (!inFence && t.startsWith("#")) {
                int lv = headingLevel(t);
                String text = headingText(t);
                if (started) {
                    if (lv <= level) {
                        break;
                    }
                } else {
                    if (text.equals(headingText)) {
                        started = true;
                        level = lv;
                        continue;
                    }
                }
            }
            if (started) {
                sb.append(line).append('\n');
            }
        }
        if (!started) {
            throw new IllegalStateException("README 里找不到标题小节：" + headingText);
        }
        return sb.toString();
    }

    /** 从「字段 | 值」表格里抠某个字段的值。 */
    private static String tableCell(String section, String field) {
        for (String line : section.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            String[] cells = t.split("\\|", -1);
            if (cells.length < 3) {
                continue;
            }
            String k = cells[1].trim();
            if (k.equals("**" + field + "**") || k.equals(field)) {
                return cells[2].trim();
            }
        }
        throw new IllegalStateException("表格里找不到字段：" + field);
    }

    /** 抠出 `` `xxx` `` 形式的反引号片段。 */
    private static List<String> backticked(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`\n]+)`").matcher(text);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    // ==================================================================
    // 源码侧事实
    // ==================================================================

    /** 事实对象：<b>只装源码侧</b>的事实。README 侧的数字一律现解析。 */
    private static final class Facts {
        String revision = "";
        String parentVersion = "";
        List<String> modules = new ArrayList<>();
        List<String> defaultPorts = new ArrayList<>();
        String portEnvVar = "";
        boolean enabledDefault = false;
        List<String> nestedGroups = new ArrayList<>();
        List<String> contentBlockTypes = new ArrayList<>();
        List<String> builtinTools = new ArrayList<>();
        List<String> jsonRpcEndpoints = new ArrayList<>();
        List<String> adminEndpoints = new ArrayList<>();
        String latestSupported = "";
        String baseline = "";
    }

    private static Facts facts() {
        Path root = repoRoot();
        Facts f = new Facts();

        // 根 pom：<revision> / parent version / <modules>（保序）
        String rootPom = stripXmlComments(readText(root.resolve(ROOT_POM)));
        Document doc = parseXml(rootPom);
        NodeList kids = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = n.getNodeName();
            if ("parent".equals(name)) {
                NodeList pk = n.getChildNodes();
                for (int j = 0; j < pk.getLength(); j++) {
                    Node p = pk.item(j);
                    if (p.getNodeType() == Node.ELEMENT_NODE && "version".equals(p.getNodeName())) {
                        f.parentVersion = p.getTextContent().trim();
                    }
                }
            } else if ("properties".equals(name)) {
                NodeList pk = n.getChildNodes();
                for (int j = 0; j < pk.getLength(); j++) {
                    Node p = pk.item(j);
                    if (p.getNodeType() == Node.ELEMENT_NODE && "revision".equals(p.getNodeName())) {
                        f.revision = p.getTextContent().trim();
                    }
                }
            } else if ("modules".equals(name)) {
                NodeList mk = n.getChildNodes();
                for (int j = 0; j < mk.getLength(); j++) {
                    Node m = mk.item(j);
                    if (m.getNodeType() == Node.ELEMENT_NODE && "module".equals(m.getNodeName())) {
                        f.modules.add(m.getTextContent().trim());
                    }
                }
            }
        }

        // 默认端口：三个 profile yml 里的 server.port（形如 ${Z_MCP_PORT:18095}）
        String[] profiles = {"application.yml", "application-hub.yml", "application-text.yml", "application-codec.yml"};
        Set<String> ports = new LinkedHashSet<>();
        for (String p : profiles) {
            Path fp = root.resolve(SERVER_RES).resolve(p);
            if (!Files.isRegularFile(fp)) {
                continue;
            }
            Matcher m = Pattern.compile("^\\s+port:\\s*\\$\\{([A-Z_]+):(\\d+)\\}", Pattern.MULTILINE).matcher(readText(fp));
            if (m.find()) {
                f.portEnvVar = m.group(1);
                ports.add(m.group(2));
            }
        }
        f.defaultPorts.addAll(ports);

        // McpProperties：顶层 enabled 的默认值 + 嵌套组数
        String props = stripComments(readText(root.resolve(MCP_PROPERTIES)));
        Matcher em = Pattern.compile("private\\s+boolean\\s+enabled\\s*=\\s*(true|false)").matcher(props);
        if (em.find()) {
            f.enabledDefault = "true".equals(em.group(1));
        }
        // 嵌套组按**配置键（字段名）**算：Session session / Sse sse / Security security /
        // RateLimit rateLimit / List<ServerConfig> servers ⇒ session / sse / security /
        // rate-limit / servers（Spring relaxed binding 下 rateLimit 的键面是 rate-limit）
        Set<String> nestedTypeNames = new LinkedHashSet<>();
        Matcher cm = Pattern.compile("public\\s+static\\s+class\\s+(\\w+)").matcher(props);
        while (cm.find()) {
            nestedTypeNames.add(cm.group(1));
        }
        Matcher fm = Pattern.compile("private\\s+([A-Z]\\w*)(?:<[^;]*?>)?\\s+(\\w+)\\s*=").matcher(props);
        while (fm.find()) {
            if (nestedTypeNames.contains(fm.group(1))) {
                f.nestedGroups.add(toKebab(fm.group(2)));
            }
        }

        // ContentBlock 的类型支
        String cb = stripComments(readText(root.resolve(CONTENT_BLOCK)));
        Matcher tm = Pattern.compile("getType\\(\\)\\s*\\{\\s*return\\s*\"([a-z_]+)\"").matcher(cb);
        while (tm.find()) {
            f.contentBlockTypes.add(tm.group(1));
        }

        // 内置工具名
        String bt = stripComments(readText(root.resolve(BUILTIN_TOOLS)));
        Matcher rm = Pattern.compile("registry\\.tool\\(\"([a-z_]+)\"\\)").matcher(bt);
        while (rm.find()) {
            f.builtinTools.add(rm.group(1));
        }

        // 端点：类级 @RequestMapping + 方法级映射
        f.jsonRpcEndpoints.addAll(endpointsOf(root.resolve(JSON_RPC_CONTROLLER)));
        f.adminEndpoints.addAll(endpointsOf(root.resolve(ADMIN_CONTROLLER)));

        // 协议版本
        String schema = stripComments(readText(root.resolve(MCP_SCHEMA)));
        Matcher lm = Pattern.compile("LATEST_SUPPORTED\\s*=\\s*\"([0-9-]+)\"").matcher(schema);
        if (lm.find()) {
            f.latestSupported = lm.group(1);
        }
        Matcher bm = Pattern.compile("实现基线[:：]\\s*([0-9-]+)").matcher(schema);
        if (bm.find()) {
            f.baseline = bm.group(1);
        }

        return f;
    }

    /** 抠出一个 Controller 的「方法 + 路径」集合。 */
    private static List<String> endpointsOf(Path file) {
        List<String> out = new ArrayList<>();
        String src = stripComments(readText(file));
        String base = "";
        Matcher rm = Pattern.compile("@RequestMapping\\(\"([^\"]*)\"\\)").matcher(src);
        if (rm.find()) {
            base = rm.group(1);
        }
        Matcher mm = Pattern.compile("@(Get|Post|Put|Delete)Mapping(?:\\(\"([^\"]*)\"\\))?").matcher(src);
        while (mm.find()) {
            String verb = mm.group(1).toUpperCase();
            String path = mm.group(2) == null ? "" : mm.group(2);
            String full = base + path;
            // 把 `${z.mcp.base-path:}` 之类的占位折叠成占位标记，README 里也是这么写的
            full = full.replace("${z.mcp.base-path:}", "").replace("${z.mcp.base-path}", "");
            out.add(verb + " " + full);
        }
        return out;
    }

    /** camelCase → kebab-case；首字母大写**不**转横线（Session → session，不是 -session）。 */
    private static String toKebab(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    // ==================================================================
    // 核对：README（现解析） ↔ Facts（源码侧）
    // ==================================================================

    private static List<String> verify(String readme, Facts f) {
        List<String> problems = new ArrayList<>();
        Path root = repoRoot();

        // ---- 基本信息表 ----
        String basic = section(readme, "基本信息");
        String readmeVersion = firstBackticked(tableCell(basic, "当前版本"));
        if (!readmeVersion.equals(f.revision)) {
            problems.add("【当前版本】README 写 " + readmeVersion + "，根 POM <revision> 是 " + f.revision);
        }
        String parentCell = tableCell(basic, "父项目");
        String expectParent = "z-boot-parent:" + f.parentVersion;
        if (!parentCell.contains(expectParent)) {
            problems.add("【父项目】README 写 " + firstBackticked(parentCell) + "，根 POM parent 是 " + expectParent);
        }
        // 模块清单：README 明说「<modules> 实测顺序」⇒ 顺序也是主张，比序列
        String moduleCell = tableCell(basic, "模块");
        List<String> readmeModules = new ArrayList<>();
        for (String b : backticked(moduleCell)) {
            if (b.startsWith("z-mcp-")) {
                readmeModules.add(b);
            }
        }
        if (!readmeModules.equals(f.modules)) {
            problems.add("【模块】README 写 " + readmeModules + "（含顺序），根 POM <modules> 是 " + f.modules);
        }
        // 默认端口
        String portCell = tableCell(basic, "默认端口");
        List<String> readmePorts = new ArrayList<>();
        for (String b : backticked(portCell)) {
            if (b.matches("\\d{4,5}")) {
                readmePorts.add(b);
            }
        }
        if (!readmePorts.equals(f.defaultPorts)) {
            problems.add("【默认端口】README 写 " + readmePorts + "，三个 profile 的 yml 是 " + f.defaultPorts);
        }
        if (!f.portEnvVar.isEmpty() && !portCell.contains(f.portEnvVar)) {
            problems.add("【默认端口】README 没提覆盖端口的环境变量 " + f.portEnvVar);
        }
        // 配置前缀与总开关默认值
        String cfgCell = tableCell(basic, "配置前缀");
        if (!cfgCell.contains("z.mcp.")) {
            problems.add("【配置前缀】README 基本信息表里没写 z.mcp.* 前缀：" + cfgCell);
        }
        String wantEnabled = String.valueOf(f.enabledDefault);
        if (!cfgCell.contains("默认 **" + wantEnabled + "**")) {
            problems.add("【总开关】README 写 " + cfgCell + "，McpProperties 的 enabled 默认是 " + wantEnabled);
        }

        // ---- 端点一览 ----
        String api = section(readme, "端点一览");
        Set<String> readmeEndpoints = new TreeSet<>();
        for (String line : api.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            String[] cells = t.split("\\|", -1);
            if (cells.length < 3) {
                continue;
            }
            for (String b : backticked(cells[1])) {
                if (b.startsWith("/") || b.contains("${base-path}")) {
                    readmeEndpoints.add(b.replace("${base-path}", ""));
                }
            }
        }
        Set<String> sourceEndpoints = new TreeSet<>();
        for (String e : f.jsonRpcEndpoints) {
            sourceEndpoints.add(e.replace(" ", " ").trim());
        }
        for (String e : f.adminEndpoints) {
            sourceEndpoints.add(e.replace(" ", " ").trim());
        }
        for (String src : sourceEndpoints) {
            String[] parts = src.split(" ", 2);
            String path = parts.length > 1 ? parts[1] : "";
            boolean covered = false;
            for (String rp : readmeEndpoints) {
                if (rp.equals(path) || rp.endsWith(path)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                problems.add("【端点】源码有 " + src + "，README 的端点一览里没有覆盖它");
            }
        }
        // 反向：README 端点表里的每一条都要在源码里有**同动词同路径**的映射。
        // 单向覆盖（源码 ⊆ README）抓不到「README 写了一个源码没有的端点」——
        // 而那正是端点路径被写错时最典型的形态。
        Set<String> sourcePairs = new TreeSet<>();
        for (String e : f.jsonRpcEndpoints) {
            sourcePairs.add(e);
        }
        for (String e : f.adminEndpoints) {
            sourcePairs.add(e);
        }
        for (String rp : readmeEndpoints) {
            if (!sourcePairs.contains(rp)) {
                problems.add("【端点】README 端点表列了 " + rp + "，源码里没有这条映射（源码映射共 "
                        + sourcePairs.size() + " 条：" + sourcePairs + "）");
            }
        }
        // 端点总数：README 端点表的数据行数
        int readmeEndpointRows = 0;
        for (String line : api.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("|") && !t.startsWith("|---") && !t.contains("方法与路径")) {
                readmeEndpointRows++;
            }
        }
        int sourceEndpointCount = f.jsonRpcEndpoints.size() + f.adminEndpoints.size();
        if (readmeEndpointRows != sourceEndpointCount) {
            problems.add("【端点】README 端点表有 " + readmeEndpointRows + " 行，源码映射共 "
                    + sourceEndpointCount + " 个（core " + f.jsonRpcEndpoints.size()
                    + " + admin " + f.adminEndpoints.size() + "）");
        }

        // ---- 能力清单：点名的实现类必须存在 ----
        String capability = section(readme, "能力清单");
        Set<String> namedClasses = new TreeSet<>();
        for (String b : backticked(capability)) {
            String simple = b.contains("/") ? b.substring(b.lastIndexOf('/') + 1) : b;
            if (simple.matches("[A-Z][A-Za-z0-9]*") && !HTTP_VERBS.contains(simple)) {
                namedClasses.add(simple);
            }
        }
        if (namedClasses.isEmpty()) {
            problems.add("【能力清单】一节里没抠出任何实现类名");
        }
        for (String cls : namedClasses) {
            if (!javaClassExists(root, cls)) {
                problems.add("【能力清单】README 点名的实现类 " + cls + " 在盘上找不到对应源码");
            }
        }

        // ---- 协议方法面：LATEST_SUPPORTED 与实现基线 ----
        String protocol = section(readme, "协议方法面");
        if (!f.latestSupported.isEmpty() && !protocol.contains(f.latestSupported)) {
            problems.add("【协议版本】README 的协议方法面节没写 McpSchema.LATEST_SUPPORTED = "
                    + f.latestSupported);
        }
        if (!f.baseline.isEmpty() && !protocol.contains(f.baseline)) {
            problems.add("【协议版本】README 的协议方法面节没写实现基线 " + f.baseline);
        }

        // ---- 技术栈：ContentBlock 五支 + raw、内置工具四件 ----
        String structure = section(readme, "项目结构");
        String apiModule = treeBlock(structure, "z-mcp-api/");
        for (String type : f.contentBlockTypes) {
            if (!apiModule.contains(type)) {
                problems.add("【ContentBlock】源码有 " + type + " 支，README 的 z-mcp-api 结构说明里没提到");
            }
        }
        // 内置工具：**钉表格行**（`| `echo` |`）而不是全文包含 ——
        // 全文包含会被同一节里别处的散文提及顶替（正文里也提到 `echo`），
        // 改掉表格行尺子反而绿。
        String tools = section(readme, "工具如何声明与分发");
        Set<String> readmeTools = new TreeSet<>();
        for (String line : tools.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            String[] cells = t.split("\\|", -1);
            if (cells.length < 3) {
                continue;
            }
            String name = firstBackticked(cells[1]);
            if (name.matches("[a-z_]+")) {
                readmeTools.add(name);
            }
        }
        Set<String> sourceTools = new TreeSet<>(f.builtinTools);
        if (!readmeTools.equals(sourceTools)) {
            problems.add("【内置工具】README 的工具表列了 " + readmeTools + "，源码注册的是 " + sourceTools);
        }

        // ---- 配置嵌套组 ----
        String cfgNote = section(readme, "端点一览");
        for (String g : f.nestedGroups) {
            if (!cfgNote.contains(g)) {
                problems.add("【配置嵌套组】McpProperties 有 " + g + " 组，README 端点一览节没提到它");
            }
        }

        // ---- 部署面：确无 Dockerfile / compose / deploy / k8s / Makefile ----
        String stack = section(readme, "技术栈");
        for (String absent : Arrays.asList("Dockerfile", "docker-compose", "deploy/", "k8s/", "Makefile")) {
            if (stack.contains("没有") && !stack.contains(absent)) {
                problems.add("【部署面】README 技术栈节说「本仓没有」某物，但没点名 " + absent);
            }
            if (existsAtRoot(root, absent)) {
                problems.add("【部署面】README 声称仓根没有 " + absent + "，但盘上存在");
            }
        }

        // ---- 文档目录：链接必须落地 ----
        String docs = section(readme, "文档目录");
        List<String> links = new ArrayList<>();
        Matcher lm = Pattern.compile("\\]\\(([^)]+)\\)").matcher(docs);
        while (lm.find()) {
            String target = lm.group(1).trim();
            if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("#")) {
                continue;
            }
            links.add(target);
        }
        if (links.isEmpty()) {
            problems.add("【文档目录】节里一个本地链接都没抠到");
        }
        for (String link : links) {
            if (!Files.exists(root.resolve(link))) {
                problems.add("【文档目录】README 里的链接目标在盘上不存在：" + link);
            }
        }

        return problems;
    }

    /**
     * 盘上是否存在某个类（按简单名在各模块的 src/main 下找 .java）。
     *
     * <p>要扫**全部模块** —— 能力清单里点名的类可能落在 starter / server 里
     * （自动装配类、可执行进程主类都不在 core）。</p>
     */
    private static boolean javaClassExists(Path root, String simpleName) {
        String file = simpleName + ".java";
        for (String module : facts().modules) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(main)) {
                if (s.anyMatch(p -> p.getFileName().toString().equals(file))) {
                    return true;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return false;
    }

    /** 仓根是否存在某物（docker-compose 支持通配前缀）。 */
    private static boolean existsAtRoot(Path root, String name) {
        if ("docker-compose".equals(name)) {
            File[] kids = root.toFile().listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (k.getName().startsWith("docker-compose")) {
                        return true;
                    }
                }
            }
            return false;
        }
        return Files.exists(root.resolve(name));
    }

    /**
     * 取项目结构树里某个节点的<b>整块</b>（该行 + 其后的续行，直到下一个同级树节点）。
     *
     * <p>只取单行会漏掉折行的说明（z-mcp-api 的 ContentBlock 五支写在续行里）。</p>
     */
    private static String treeBlock(String structure, String needle) {
        String[] lines = structure.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String line : lines) {
            boolean startsNode = line.matches("^(?:\\|\\s)*(?:├──|└──).*");
            if (startsNode) {
                if (in) {
                    break;
                }
                if (line.contains(needle)) {
                    in = true;
                }
            }
            if (in) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String lineContaining(String text, String needle) {
        for (String line : text.split("\n", -1)) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return "";
    }

    private static String firstBackticked(String s) {
        List<String> b = backticked(s);
        return b.isEmpty() ? "" : b.get(0);
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    public void README主张与源码重算逐条对账() {
        List<String> problems = verify(readme(), facts());
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("z-mcp README 与源码不一致，共 " + problems.size() + " 处：");
            for (String p : problems) {
                sb.append("\n  - ").append(p);
            }
            fail(sb.toString());
        }
    }

    @Test
    public void 不许冤枉健康页() {
        List<String> problems = verify(readme(), facts());
        assertTrue("真实 README 被判红：\n" + String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void 尺子的牙口_版本改错必红() {
        String readme = readme();
        String mutated = readme.replace("| **当前版本** | `" + facts().revision + "`",
                "| **当前版本** | `9.9.9`");
        assertFalse("合成变异没生效：README 里没找到当前版本", mutated.equals(readme));
        List<String> problems = verify(mutated, facts());
        assertFalse("README 版本号改成 9.9.9，尺子居然没报红", problems.isEmpty());
        assertTrue("报错没指出被改坏的数字：\n" + String.join("\n", problems),
                problems.toString().contains("9.9.9"));
    }

    @Test
    public void 尺子的牙口_模块清单改错必红() {
        String readme = readme();
        String mutated = readme.replace("`z-mcp-server`", "`z-mcp-serveX`");
        assertFalse("合成变异没生效：README 里没找到 z-mcp-server", mutated.equals(readme));
        List<String> problems = verify(mutated, facts());
        assertFalse("README 模块清单被改，尺子居然没报红", problems.isEmpty());
    }

    @Test
    public void 尺子的牙口_端口改错必红() {
        String readme = readme();
        String mutated = readme.replace("`18095`", "`18099`");
        assertFalse("合成变异没生效：README 里没找到 18095", mutated.equals(readme));
        List<String> problems = verify(mutated, facts());
        assertFalse("README 默认端口被改，尺子居然没报红", problems.isEmpty());
    }

    @Test
    public void 尺子的牙口_总开关默认值改错必红() {
        String readme = readme();
        String mutated = readme.replace("总开关 `z.mcp.enabled` 默认 **false**",
                "总开关 `z.mcp.enabled` 默认 **true**");
        assertFalse("合成变异没生效：README 里没找到总开关默认值", mutated.equals(readme));
        List<String> problems = verify(mutated, facts());
        assertFalse("README 总开关默认值被改，尺子居然没报红", problems.isEmpty());
    }

    @Test
    public void 尺子的牙口_内置工具名改错必红() {
        String readme = readme();
        String mutated = readme.replace("| `echo` |", "| `echoX` |");
        assertFalse("合成变异没生效：README 里没找到 echo 工具行", mutated.equals(readme));
        List<String> problems = verify(mutated, facts());
        assertFalse("README 内置工具名被改，尺子居然没报红", problems.isEmpty());
    }

    @Test
    public void 尺子的牙口_能力清单类名改错必红() {
        String readme = readme();
        String mutated = readme.replace("`core/protocol/McpSchema`", "`core/protocol/McpSchemaX`");
        assertFalse("合成变异没生效：README 里没找到 McpSchema", mutated.equals(readme));
        List<String> problems = verify(mutated, facts());
        assertFalse("README 能力清单的类名被改，尺子居然没报红", problems.isEmpty());
    }

    @Test
    public void 尺子的牙口_端点路径改错必红() {
        String readme = readme();
        String mutated = readme.replace("`GET ${base-path}/mcp/info`", "`GET ${base-path}/mcp/infX`");
        assertFalse("合成变异没生效：README 端点表里没找到 /mcp/info 那一行", mutated.equals(readme));
        List<String> problems = verify(mutated, facts());
        assertFalse("README 端点路径被改，尺子居然没报红", problems.isEmpty());
        assertTrue("报错没指出被改坏的端点：\n" + String.join("\n", problems),
                problems.toString().contains("mcp/infX"));
    }

    @Test
    public void 尺子的牙口_文档链接失效必红() {
        String readme = readme();
        // 假路径**必须拆两段拼接**：源码里若出现连续的「不存在的路径」字面量，
        // depth_check 会判成「悬空字面量」违规（它连注释行一起扫）。
        String brokenLink = "_doc/001_arch/" + "99-not-exist.md";
        String mutated = readme.replace("_doc/001_arch/00-overview.md", brokenLink);
        assertFalse("合成变异没生效：README 里没找到 00-overview.md 链接", mutated.equals(readme));
        List<String> problems = verify(mutated, facts());
        assertFalse("README 文档链接指向不存在的文件，尺子居然没报红", problems.isEmpty());
    }

    @Test
    public void 守卫生效确认() {
        String readme = readme();
        assertTrue("读到的 README 不含仓名，仓根定位可能错了", readme.contains("z-mcp"));
        assertNotNull("facts 应能采到", facts());
        assertEquals("Controller 名单不应有重复", facts().jsonRpcEndpoints.size(),
                new TreeSet<>(facts().jsonRpcEndpoints).size());
    }

    private static String readme() {
        return readText(repoRoot().resolve("README.md"));
    }
}
