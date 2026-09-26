package com.zifang.z.mcp.server;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link Boot#until} 的**失败消息**也得是可用的量具.
 *
 * <p>这一层不是被测代码, 但注入验收的证据全押在它打印的那句"最后一次观察"上: 等待每 100 ms 打一次
 * {@code tools/list}, 两轮之间就能把自家限流预算(默认 300/min)打穿, 于是同一变异体连跑 3 轮会有
 * 2 轮的消息只剩"实得 429" —— 目录里到底是 12 条还是 18 条这张照片丢了, 那条负向断言就从证据退化成
 * 运气。判据是"什么时候该说读不到、什么时候该把最后一次真读到的值留着", 这正好是量具最容易做错的一格。
 */
public class BootWaitTest {

    private static final long DEADLINE_MS = 600L;

    /** 传输错是量具自己的失败, 不许顶掉上一次真读到的状态. */
    @Test
    public void a_transport_error_does_not_erase_the_last_real_observation() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        try {
            Boot.until(System.currentTimeMillis() + DEADLINE_MS, "一个永远不成立的条件",
                    new Boot.Probe() {
                        @Override public String inspect() throws Exception {
                            if (calls.incrementAndGet() == 1) {
                                throw new Boot.ExpectationPending("目录里现在是 [18 条]");
                            }
                            throw new IOException("429 rate limit exceeded");
                        }
                    });
            fail("条件不成立时必须判失败");
        } catch (AssertionError e) {
            String msg = e.getMessage();
            assertTrue("观察位上该是最后一次真读到的目录, 不是传输错: " + msg,
                    msg.contains("最后一次观察: 目录里现在是 [18 条]"));
            assertTrue("自撞限流这件事仍然要说得出: " + msg, msg.contains("429"));
        }
        assertTrue("等待确实按周期在敲, 不是一次就放弃: " + calls, calls.intValue() > 2);
    }

    /** 一次都没读到过的时候, 不能假装观察到了一份空状态. */
    @Test
    public void a_wait_that_never_got_a_reading_says_so_instead_of_inventing_one() throws Exception {
        try {
            Boot.until(System.currentTimeMillis() + DEADLINE_MS, "一个从没读通过的条件",
                    new Boot.Probe() {
                        @Override public String inspect() throws Exception {
                            throw new IOException("connection refused");
                        }
                    });
            fail("条件不成立时必须判失败");
        } catch (AssertionError e) {
            String msg = e.getMessage();
            assertTrue("一次都没读到就要明说, 别让人把空话当照片: " + msg,
                    msg.contains("最后一次观察: (一次都没读到)"));
            assertTrue("失败原因还是要给出来: " + msg, msg.contains("connection refused"));
        }
    }
}
