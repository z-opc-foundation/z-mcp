package com.zifang.z.mcp.server.role;

import com.zifang.z.mcp.api.dto.ToolAnnotations;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code text} 角色的工具: 纯函数式的文本加工, 不碰网络不碰磁盘.
 *
 * <p>两条都刻意做成"结果能自己复算"的量(码点数/字节数/大小写转换), 这样 {@code tools/call} 的返回
 * 值和广告出去的 {@code inputSchema} 都能被测试与使用者一眼判对错 —— 一个报不出可验证数字的工具
 * 不值得挂进目录.
 *
 * <p>{@code text_transform} 的合法取值是 {@link #STYLES} 的键集<em>生成</em>出来的, 不是手抄第二遍:
 * schema 广告一个没有实现的功能, 客户端只会拿到一次失败.
 */
@Component
@Profile("text")
public class TextTools {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    interface Style {
        String apply(String text);
    }

    static final Map<String, Style> STYLES;

    static {
        Map<String, Style> m = new LinkedHashMap<String, Style>();
        // 大小写一律走 Locale.ROOT: 用默认区域时同一把输入在土耳其语的 JVM 上
        // "i".toUpperCase() 会得到 "İ", 一台机器绿一台机器红.
        m.put("upper", new Style() {
            @Override public String apply(String text) { return text.toUpperCase(Locale.ROOT); }
        });
        m.put("lower", new Style() {
            @Override public String apply(String text) { return text.toLowerCase(Locale.ROOT); }
        });
        m.put("snake", new Style() {
            @Override public String apply(String text) { return join(words(text), "_"); }
        });
        m.put("kebab", new Style() {
            @Override public String apply(String text) { return join(words(text), "-"); }
        });
        m.put("title", new Style() {
            @Override public String apply(String text) {
                List<String> w = words(text);
                List<String> out = new ArrayList<String>(w.size());
                for (String s : w) out.add(capitalize(s));
                return join(out, " ");
            }
        });
        m.put("reverse", new Style() {
            @Override public String apply(String text) { return reverseCodePoints(text); }
        });
        STYLES = Collections.unmodifiableMap(m);
    }

    TextTools(McpRegistry registry) {
        registry.tool("text_stats")
                .title("Text statistics")
                .description("统计文本: 字符数(按 Unicode 码点, 不是 UTF-16 单元)/词数/行数/UTF-8 字节数")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                        + "\"description\":\"要统计的文本\"}},\"required\":[\"text\"],"
                        + "\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("文本统计"))
                .register(args -> {
                    String text = ToolArgs.required(args, "text");
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("characters", Integer.valueOf(text.codePointCount(0, text.length())));
                    out.put("words", Integer.valueOf(wordCount(text)));
                    out.put("lines", Integer.valueOf(lineCount(text)));
                    out.put("utf8Bytes", Integer.valueOf(text.getBytes(UTF8).length));
                    return out;
                });

        registry.tool("text_transform")
                .title("Text case transform")
                .description("命名风格/大小写转换: " + ToolArgs.joinList(STYLES.keySet(), " "))
                .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},"
                        + "\"to\":{\"type\":\"string\",\"enum\":["
                        + ToolArgs.enumLiteral(STYLES.keySet()) + "],"
                        + "\"description\":\"目标风格\"}},"
                        + "\"required\":[\"text\",\"to\"],\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("文本转换"))
                .register(args -> transform(ToolArgs.required(args, "text"),
                        ToolArgs.required(args, "to")));
    }

    static String transform(String text, String to) {
        Style style = STYLES.get(to.trim().toLowerCase(Locale.ROOT));
        if (style == null) {
            throw new IllegalArgumentException("unknown target style '" + to + "', expected one of "
                    + ToolArgs.joinList(STYLES.keySet(), "|"));
        }
        return style.apply(text);
    }

    static int wordCount(String text) {
        String t = text.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    /** 空串 0 行; {@code "a\n"} 是 1 行而不是"1 行 + 一个空行"(行尾换行是行终止符). */
    static int lineCount(String text) {
        return text.isEmpty() ? 0 : text.split("\r\n|\r|\n").length;
    }

    /** 按词切: 非字母数字断开, camelCase 的驼峰处断开(连续大写留在同一个词里). */
    static List<String> words(String text) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (!Character.isLetterOrDigit(cp)) {
                flush(cur, out);
            } else if (Character.isUpperCase(cp) && cur.length() > 0
                    && Character.isLowerCase(text.codePointBefore(i))) {
                flush(cur, out);
                cur.appendCodePoint(Character.toLowerCase(cp));
            } else {
                cur.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    private static String capitalize(String word) {
        if (word.isEmpty()) return word;
        int first = word.codePointAt(0);
        int n = Character.charCount(first);
        return word.substring(0, n).toUpperCase(Locale.ROOT) + word.substring(n);
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** 按码点倒序, 所以代理对(emoji)不会被拆成两个乱字符. */
    private static String reverseCodePoints(String text) {
        int[] cps = new int[text.codePointCount(0, text.length())];
        int index = 0;
        int n = 0;
        while (index < text.length()) {
            int cp = text.codePointAt(index);
            cps[n++] = cp;
            index += Character.charCount(cp);
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = n - 1; i >= 0; i--) sb.appendCodePoint(cps[i]);
        return sb.toString();
    }
}
