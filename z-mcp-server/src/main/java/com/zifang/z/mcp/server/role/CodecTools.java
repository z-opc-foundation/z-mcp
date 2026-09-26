package com.zifang.z.mcp.server.role;

import com.zifang.z.mcp.api.dto.ToolAnnotations;
import com.zifang.z.mcp.core.registry.McpRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code codec} 角色的工具: 编码与摘要, 纯计算.
 *
 * <p>摘要一律走 JDK 的 {@link MessageDigest} —— 自己实现一遍 SHA-256 才是事故来源.
 * 描述里点明 md5/sha-1 只能当校验和用: 工具是广告给 LLM 的, 广告语少写一句,
 * 就有人拿它去做"完整性校验".
 */
@Component
@Profile("codec")
public class CodecTools {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 广告出去的算法名 → JDK 的 MessageDigest 名. schema 的 enum 由这张表生成, 不另抄一份. */
    static final Map<String, String> ALGORITHMS;
    static final String DEFAULT_ALGORITHM = "sha-256";

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("md5", "MD5");
        m.put("sha-1", "SHA-1");
        m.put(DEFAULT_ALGORITHM, "SHA-256");
        m.put("sha-512", "SHA-512");
        ALGORITHMS = Collections.unmodifiableMap(m);
    }

    CodecTools(McpRegistry registry) {
        registry.tool("base64_codec")
                .title("Base64 encode/decode")
                .description("UTF-8 文本与 Base64 互转; urlSafe=true 用 -_ 字母表且不带填充")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                        + "\"description\":\"待编码文本或待解码的 Base64 串\"},"
                        + "\"mode\":{\"type\":\"string\",\"enum\":[\"encode\",\"decode\"]},"
                        + "\"urlSafe\":{\"type\":\"boolean\",\"description"
                        + "\":\"是否用 URL 安全字母表, 默认 false\"}}"
                        + ",\"required\":[\"text\",\"mode\"],\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("Base64 编解码"))
                .register(args -> {
                    String text = ToolArgs.required(args, "text");
                    String mode = ToolArgs.required(args, "mode").trim().toLowerCase(Locale.ROOT);
                    boolean urlSafe = ToolArgs.flag(args, "urlSafe");
                    if ("encode".equals(mode)) {
                        Base64.Encoder e = urlSafe
                                ? Base64.getUrlEncoder().withoutPadding() : Base64.getEncoder();
                        return e.encodeToString(text.getBytes(UTF8));
                    }
                    if ("decode".equals(mode)) {
                        Base64.Decoder d = urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder();
                        try {
                            return new String(d.decode(text.trim()), UTF8);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("not valid "
                                    + (urlSafe ? "URL-safe " : "") + "Base64: " + e.getMessage());
                        }
                    }
                    throw new IllegalArgumentException("mode must be encode or decode, got: " + mode);
                });

        registry.tool("hash_digest")
                .title("Hash digest")
                .description("算 UTF-8 文本的摘要并返回小写十六进制; 算法 "
                        + ToolArgs.joinList(new ArrayList<String>(ALGORITHMS.keySet()), "|")
                        + "(默认 " + DEFAULT_ALGORITHM + "). 只作校验和/去重指纹用,"
                        + " md5 与 sha-1 不具备抗碰撞性, 别拿它做安全校验")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},"
                        + "\"algorithm\":{\"type\":\"string\",\"enum\":["
                        + ToolArgs.enumLiteral(ALGORITHMS.keySet()) + "]}}"
                        + ",\"required\":[\"text\"],\"additionalProperties\":false}")
                .annotations(ToolAnnotations.readOnly("摘要计算"))
                .register(args -> {
                    String text = ToolArgs.required(args, "text");
                    String algorithm = ToolArgs.optional(args, "algorithm", DEFAULT_ALGORITHM)
                            .trim().toLowerCase(Locale.ROOT);
                    MessageDigest digest = digestOf(algorithm);
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("algorithm", algorithm);
                    out.put("hex", toHex(digest.digest(text.getBytes(UTF8))));
                    return out;
                });
    }

    static MessageDigest digestOf(String algorithm) {
        String jdk = ALGORITHMS.get(algorithm);
        if (jdk == null) {
            throw new IllegalArgumentException("unsupported algorithm: " + algorithm
                    + ", expected one of " + ToolArgs.joinList(ALGORITHMS.keySet(), "|"));
        }
        try {
            return MessageDigest.getInstance(jdk);
        } catch (NoSuchAlgorithmException e) {
            // 四个算法属于 JDK 规范必须提供的集合; 真缺了就是这套 JRE 被裁剪过, 报清楚别装作能算.
            throw new IllegalStateException("this JVM provides no " + jdk + " digest", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}
