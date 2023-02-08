/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.network;

import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.WorkerPool;
import io.questdb.std.*;
import io.questdb.std.datetime.microtime.Timestamps;
import io.questdb.std.datetime.millitime.MillisecondClockImpl;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static io.questdb.network.IODispatcher.*;
import static io.questdb.test.tools.TestUtils.assertMemoryLeak;

@RunWith(Parameterized.class)
public class IODispatcherTimeoutsTest {
    private static final Log LOG = LogFactory.getLog(IODispatcherTimeoutsTest.class);
    private static final String TICK = "tick";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 9001;
    final static IODispatcherConfiguration dispatcherConf = new DefaultIODispatcherConfiguration();
    static WorkerPool workerPool;
    final static IODispatcher<TestConnectionContext> dispatcher = IODispatchers.create(dispatcherConf, new MutableIOContextFactory<>(TestConnectionContext::new, 8));
    final static TestRequestProcessor processor = new TestRequestProcessor();
    private static long timeoutMillis = -1L;
    private static long timeoutCount = 0L;
    private static long readCount = 0L;
    private static boolean readjustOnRead = false;
    private static Rnd rnd = new Rnd();
    private final int workerCount;
    public IODispatcherTimeoutsTest(int workerCount) {
        this.workerCount = workerCount;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{1}, {4}});
    }

    @Before
    public void setUp() {
        workerPool = new WorkerPool(() -> workerCount);
        workerPool.assign(dispatcher);
        workerPool.assign((workerId, runStatus) -> dispatcher.processIOQueue(processor));
        workerPool.start();
    }

    @After
    public void tearDown() {
        workerPool = null;
        timeoutCount = 0;
        readCount = 0;
    }

    @Test
    public void testNoTicks() throws Exception {
        timeoutMillis = 10;
        readjustOnRead = false;
        assertMemoryLeak(() -> {
            int N = 1;
            tick(N, 150);
            Assert.assertTrue(timeoutCount >= 2 * 150/timeoutMillis);
            Assert.assertEquals(N, readCount);
        });
    }

    @Test
    public void testRegularTicksAndTimeouts() throws Exception {
        timeoutMillis = 10;
        readjustOnRead = false;
        int N = 10;
        assertMemoryLeak(() -> {
            tick(N, 20);
            Assert.assertTrue(timeoutCount >= ((N + 1)*20) / timeoutMillis);
            Assert.assertEquals(N, readCount);
        });
    }

    @Test
    public void testAdjustNextOnEveryRead() throws Exception {
        timeoutMillis = 20;
        readjustOnRead = true;

        assertMemoryLeak(() -> {
            tick(10, 20);
            Assert.assertEquals(10, readCount);
            Assert.assertTrue(timeoutCount > 10);
        });
    }

    @Test
    public void testAdjustNextOnEveryRead2() throws Exception {
        timeoutMillis = 50;
        readjustOnRead = true;

        assertMemoryLeak(() -> {
            tick(10, 5);
            Assert.assertEquals(10, readCount);
            Assert.assertEquals(1, timeoutCount); // unlucky one, always delayed
        });
    }
    @Test
    public void testSeveralParallelConnection() throws Exception {
        timeoutMillis = 10;
        readjustOnRead = false;
        assertMemoryLeak(() -> {
            nParallelConnection(8, 10, 50);
        });
    }

    @Test
    public void testSeveralParallelConnectionAdjust() throws Exception {
        timeoutMillis = 10;
        readjustOnRead = true;
        Rnd rnd = new Rnd();
        assertMemoryLeak(() -> {
            nParallelConnection(22, 10, 20);
        });
    }

    private static void nParallelConnection(int N, int pingCount, long pingDelay) throws InterruptedException {
        Thread[] threads = new Thread[N];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread() {
                @Override
                public void run() {
                    int delayJitter = rnd.nextInt(5);
                    long delay = pingDelay + delayJitter;
                    tickInner(pingCount, delay);
                    Os.sleep(delay);
                }
            };
        }

        for (int i = 0; i < threads.length; i++) {
            int startJitter = rnd.nextInt(10);
            Os.sleep(startJitter);
            threads[i].start();
        }
        for (int i = 0; i < threads.length; i++) {
           threads[i].join();
        }
        workerPool.halt();
    }

    private static void tick(long N, long delayMillis) {
       tickInner(N, delayMillis);
       Os.sleep(delayMillis);
       workerPool.halt();
    }

    private static void tickInner(long N, long delayMillis) {
        long bufSize = TICK.length();
        long inf = Net.getAddrInfo(HOST, PORT);
        int fd = Net.socketTcp(true);
        try {
            if (Net.connectAddrInfo(fd, inf) != 0) {
                LOG.error()
                        .$("could not connect [host=").$(HOST)
                        .$(", port=").$(PORT)
                        .$(", errno=").$(Os.errno())
                        .I$();
            } else {
                long buf = Unsafe.malloc(bufSize, MemoryTag.NATIVE_DEFAULT);
                Chars.asciiStrCpy(TICK, buf);
                try {
                    Os.sleep(delayMillis);
                    for (int i = 0; i < N; i++) {
                        int n = Net.send(fd, buf, TICK.length());
                        if (n < 0) {
                            LOG.error().$("connection lost").$();
                            break;
                        }
                        Assert.assertEquals(n, TICK.length());
                        Os.sleep(delayMillis);
                    }
                } finally {
                    Unsafe.free(buf, bufSize, MemoryTag.NATIVE_DEFAULT);
                }
            }
        } finally {
            Net.freeAddrInfo(inf);
            Net.close(fd);
        }
    }

    private static class TestConnectionContext extends AbstractMutableIOContext<TestConnectionContext> {
        private long nextMillis = 0;
        private long prevTimeoutMillis = 0;
        private long prevReadMillis = 0;
        private long readProcessing = Long.MIN_VALUE;
        private long timeoutProcessing = Long.MIN_VALUE;

        @Override
        public void clear() {
            nextMillis = 0;
            prevTimeoutMillis = 0;
            LOG.debug().$("context fd: ").$(fd).$(" cleared").$();
        }

        @Override
        public void close() {
            LOG.debug().$("context fd: ").$(fd).$(" closed").$();
        }

        public void onRead() {
            readProcessing = Long.MAX_VALUE;
            final long buffer = Unsafe.malloc(TICK.length(), MemoryTag.NATIVE_DEFAULT);
            try {
                int n = Net.recv(getFd(), buffer, TICK.length());
                if (n > 0) {
                    readCount+=1;
                    long now = Os.currentTimeMicros() / Timestamps.MILLI_MICROS;
                    if (readjustOnRead) {
                        // in real life isTimeout and onRead will be called from different threads.
                        prevReadMillis = now;
                        nextMillis = now + timeoutMillis;
                    }
                    LOG.debug().$("read ").$(now).$();
                    getDispatcher().registerChannel(this, IOOperation.READ);
                } else {
                    getDispatcher().disconnect(this, DISCONNECT_REASON_PEER_DISCONNECT_AT_RECV);
                }
            } finally {
                Unsafe.free(buffer, TICK.length(), MemoryTag.NATIVE_DEFAULT);
                readProcessing = Long.MIN_VALUE;
            }
        }

        public void onWrite() {
            getDispatcher().registerChannel(this, IOOperation.WRITE);
        }

        @Override
        public boolean isTimeout(long nowMillis) {
            // The dispatcher should not attempt to check the timeout while processing read or timeout event
            Assert.assertTrue(nowMillis > readProcessing);
            Assert.assertTrue(nowMillis > timeoutProcessing);
            if (nowMillis >= nextMillis) {
                nextMillis = nowMillis + timeoutMillis;
                return true;
            }
            return false;
        }

        public void onTimeout() {
            timeoutCount+=1;
            timeoutProcessing = Long.MAX_VALUE;
            long now = MillisecondClockImpl.INSTANCE.getTicks();
            LOG.debug().$("timeout: ").$(now).$();

            long delta = now - prevTimeoutMillis;
            if (readjustOnRead) {
                // timeout should be always timeoutMillis away from the latest read event
                Assert.assertTrue(prevReadMillis == 0 || now - prevReadMillis >= timeoutMillis);
            } else {
                // not much we can guarantee here. delta can be less than timeoutMillis due to queue processing/delay,
                // but we should not lose timeouts anyway (delta <= 2 * timeoutMillis)
                Assert.assertTrue(prevTimeoutMillis == 0 || delta <= 2 * timeoutMillis);
            }
            prevTimeoutMillis = now;
            timeoutProcessing = Long.MIN_VALUE;
        }
    }

    private static class TestRequestProcessor implements IORequestProcessor<TestConnectionContext> {
        @Override
        public boolean onRequest(int operation, TestConnectionContext context) {
            boolean io = false;

            if (IOOperation.isRead(operation)) {
                context.onRead();
                io = true;
            }
            if (IOOperation.isWrite(operation)) {
                context.onWrite();
                io = true;
            }

            if (!io) {
                // trigger if idle only
                if (IOOperation.isTimeout(operation)) {
                    context.onTimeout();
                    context.getDispatcher().registerChannel(context, IOOperation.READ);
                }
            }
            return true;
        }
    }
}
