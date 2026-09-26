package com.zifang.z.mcp.core.security;

import com.zifang.z.mcp.core.properties.McpProperties;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * bearer 白名单的 401 挑战形状(RFC 6750 §3.1).
 *
 * <p>为什么单独钉这一层: 0.1.x 把"没带令牌"和"令牌不对"合成一句 {@code missing or invalid
 * bearer token} + 一个 {@code error="invalid_request"}。那三个值对客户端是**不同指令**——
 * 裸挑战=去取凭证, {@code invalid_token}=凭证被拒(可以换新令牌重来),
 * {@code invalid_request}=请求不成形(重试无意义)。把它们混报, 配错 token 的客户端
 * 会以为是自己姿势不对而无限重试, 而运维从响应里看不出是哪种。
 */
public class TransportSecurityGuardAuthTest {

    private static final String REALM = "Bearer realm=\"z-mcp\"";

    private McpProperties properties;
    private TransportSecurityGuard guard;

    @Before
    public void setUp() {
        properties = new McpProperties();
        properties.getSecurity().setBearerTokens(Collections.singletonList("s3cret"));
        guard = new TransportSecurityGuard(properties);
    }

    private TransportSecurityGuard.Violation v(String authorization) {
        return guard.checkRequest(null, "mcp.internal:8080", authorization);
    }

    @Test
    public void an_absent_credential_gets_a_bare_challenge() {
        TransportSecurityGuard.Violation violation = v(null);
        assertEquals(401, violation.httpStatus);
        assertEquals(REALM, violation.wwwAuthenticate);
        assertTrue(violation.message, violation.message.contains("missing"));
    }

    @Test
    public void a_rejected_token_says_invalid_token_not_invalid_request() {
        TransportSecurityGuard.Violation violation = v("Bearer nope");
        assertEquals(401, violation.httpStatus);
        assertEquals(REALM + ", error=\"invalid_token\"", violation.wwwAuthenticate);
        assertFalse("配错 token 被报成\"请求不成形\"会把客户端推向无意义重试",
                violation.wwwAuthenticate.contains("invalid_request"));
    }

    @Test
    public void a_non_bearer_scheme_is_a_malformed_request() {
        assertEquals(REALM + ", error=\"invalid_request\"", v("Basic ZGVmYXVsdA==").wwwAuthenticate);
    }

    @Test
    public void a_bearer_header_with_no_token_value_is_a_malformed_request() {
        // "带了 Authorization 头但没有值"和"头都没有"是两回事: 前者客户端已经理解错了姿势,
        // 报 invalid_request 才说得出"你的请求本身不成形"。
        for (String bad : new String[]{"Bearer", "Bearer   ", "Bearer\t"}) {
            assertEquals("[" + bad + "]", REALM + ", error=\"invalid_request\"", v(bad).wwwAuthenticate);
            assertNull("[" + bad + "]", TransportSecurityGuard.bearerOf(bad));
        }
    }

    @Test
    public void the_configured_token_still_opens_the_door() {
        // 上面四条的对照: 如果闸写成"永远 401", 它们会全部通过而没人发现.
        assertNull(v("Bearer s3cret"));
        assertEquals("s3cret", TransportSecurityGuard.bearerOf("bearer s3cret"));
    }

    @Test
    public void an_empty_allowlist_means_no_auth_at_all() {
        // 文档写的是"空 = 关闭鉴权", 那必须包括"带着一堆乱七八糟凭证头的请求也不挡".
        properties.getSecurity().setBearerTokens(Collections.<String>emptyList());
        assertNull(v(null));
        assertNull(v("Bearer whatever"));
        assertNull(v("Basic ZGVmYXVsdA=="));
    }
}
