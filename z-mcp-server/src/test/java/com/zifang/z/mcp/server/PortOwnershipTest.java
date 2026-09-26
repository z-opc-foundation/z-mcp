package com.zifang.z.mcp.server;

import org.junit.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.BindException;
import java.net.ServerSocket;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * "端口归我了" 是一个需要被证的断言, 不是 OS 送出的默认值.
 *
 * <p>起因是本机一次全量构建里 {@code RolesTest} 的偶发红: 客户端拿到
 * {@code 404 <h1>404 Not Found</h1>No context found for request}, 读起来像应用没挂 {@code /mcp}。
 * 真相在本机量到了: 同一时刻另一个进程的 web hook 正占着 {@code 127.0.0.1:61004}, 而那正是本次
 * 构建里 Tomcat 报告 "started on port(s): 61004" 的那个号 —— 逐字打那个端口要这份 404 就能复现。
 *
 * <p>所以 {@link Boot#app} 必须把应用绑在回环地址上, 让"端口被更具体的绑定影掉"这件事在 bind
 * 当场变成一次失败。这条守卫在 mac 上才有牙(实测: 通配绑定静默共存且收不到流量); 在 Linux 上
 * 通配绑定本身就被 EADDRINUSE 拒掉(实测 250/4.15), 于是这里两种 OS 都是绿的, 但只有前者真的在量东西。
 */
public class PortOwnershipTest {

    @Test public void a_port_someone_else_holds_on_loopback_never_counts_as_ours() throws Exception {
        ServerSocket squatter = new ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"));
        int taken = squatter.getLocalPort();

        ConfigurableApplicationContext ctx = null;
        try {
            ctx = Boot.app("--spring.profiles.active=text", "--server.port=" + taken);
            fail("应用自称起在 " + taken + ", 可那个端口上真正接走 127.0.0.1 流量的仍是占着它的 socket。"
                    + " 客户端会读到别人的回执, 用例里就成了\"initialize 404\"这类查不出根因的红 —— "
                    + "绑通配在这里就是没绑到东西");
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            assertTrue("冲突必须是 bind 失败, 实得 " + root.getClass().getName() + ": " + root,
                    root instanceof BindException);
        } finally {
            if (ctx != null) ctx.close();
            squatter.close();
        }
    }
}
