package edu.washington.cs.oneswarm.f2f.datagram;

import static edu.washington.cs.oneswarm.f2f.datagram.DatagramConnection.MAX_DATAGRAM_PAYLOAD_SIZE;
import static edu.washington.cs.oneswarm.f2f.datagram.DatagramConnection.MAX_DATAGRAM_SIZE;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.LinkedList;

import org.gudy.azureus2.core3.util.DirectByteBuffer;
import org.gudy.azureus2.core3.util.DirectByteBufferPool;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testng.Assert;

import com.aelitis.azureus.core.peermanager.messaging.Message;

import edu.washington.cs.oneswarm.f2f.datagram.DatagramConnection.ReceiveState;
import edu.washington.cs.oneswarm.f2f.datagram.DatagramConnection.SendState;
import edu.washington.cs.oneswarm.f2f.messaging.OSF2FChannelDataMsg;
import edu.washington.cs.oneswarm.f2f.messaging.OSF2FDatagramInit;
import edu.washington.cs.oneswarm.f2f.messaging.OSF2FDatagramOk;
import edu.washington.cs.oneswarm.f2f.messaging.OSF2FMessage;
import edu.washington.cs.oneswarm.f2f.messaging.OSF2FMessageFactory;
import edu.washington.cs.oneswarm.f2f.servicesharing.OSF2FServiceDataMsg;
import edu.washington.cs.oneswarm.test.util.OneSwarmTestBase;
import edu.washington.cs.oneswarm.test.util.TestUtils;

public class DatagramConnectionTest extends OneSwarmTestBase {
    public static final byte AL = DirectByteBuffer.AL_MSG;
    public static final byte SS = DirectByteBuffer.SS_MSG;
    public static final int LOTS_OF_TOKENS = 100 * 1000 * 1000;

    // Leave room for 1 word channel_id + 1 word window size + 2 word service header
    public static final int MAX_CHANNEL_MESSAGE_PAYLOAD_SIZE = MAX_DATAGRAM_PAYLOAD_SIZE - 16;

    private static class MockDatagramConnectionManager implements DatagramConnectionManager {
        DatagramSocket socket;
        final String desc;
        private DatagramConnection conn;
        private final DatagramRateLimiter rateLimiter = new DatagramRateLimiter();

        public MockDatagramConnectionManager(String desc) throws SocketException {
            this.socket = new DatagramSocket();
            socket.setSoTimeout(1000);
            this.desc = desc;
            rateLimiter.setTokenBucketSize(LOTS_OF_TOKENS);
        }

        @Override
        public void send(DatagramPacket packet, boolean lan) throws IOException {
            // System.out.println(desc + ": sending packet: " +
            // packet.getSocketAddress());
            socket.send(packet);
        }

        @Override
        public void register(DatagramConnection connection) {
            conn = connection;
            rateLimiter.addQueue(connection);
        }

        @Override
        public int getPort() {
            return socket.getLocalPort();
        }

        @Override
        public void deregister(DatagramConnection conn) {
            rateLimiter.removeQueue(conn);
            this.conn = null;
        }

        void receive() throws IOException {
            DatagramPacket p = new DatagramPacket(new byte[1450], 0, 1450);
            socket.receive(p);
            // System.out.println(desc + ": received packet: " +
            // p.getSocketAddress());

            conn.messageReceived(p);
        }

        public void socketUpdated() throws IOException {
            int oldPort = this.socket.getLocalPort();
            this.socket.close();
            this.socket = new DatagramSocket();
            System.out.println("port change: " + oldPort + "->" + socket.getLocalPort());
            conn.reInitialize();
        }

        @Override
        public DatagramRateLimiter getMainRateLimiter() {
            return rateLimiter;
        }

        @SuppressWarnings("unused")
        public String getDesc() {
            return this.desc;
        }
    }

    MockDatagramConnectionManager manager1;
    MockDatagramConnectionManager manager2;
    DatagramConnection conn1;
    DatagramConnection conn2;
    LinkedList<Message> conn1Incoming;
    LinkedList<Message> conn2Incoming;
    boolean skipOkPackets;

    public void setupLogging() {
        logFinest(DatagramEncrypter.logger);
        logFinest(DatagramDecrypter.logger);
        logFinest(DatagramConnection.logger);
        logFinest(DatagramRateLimitedChannelQueue.logger);
        logFinest(DatagramRateLimiter.logger);
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        OSF2FMessageFactory.init();
    }

    @After
    public void tearDown() throws Exception {
        manager1.socket.close();
        manager2.socket.close();
    }

    @Before
    public void setUp() throws Exception {
        setupLogging();
        skipOkPackets = true;
        final InetAddress localhost = InetAddress.getByName("127.0.0.1");
        manager1 = new MockDatagramConnectionManager("1");
        conn1Incoming = new LinkedList<Message>();
        conn1 = new DatagramConnection(manager1, new DatagramListener() {

            @Override
            public void sendDatagramOk(OSF2FDatagramOk osf2fDatagramOk) {
                // Fake instant reception at conn2.
                conn2.okMessageReceived();
            }

            @Override
            public InetAddress getRemoteIp() {
                return localhost;
            }

            @Override
            public void datagramDecoded(Message message, int size) {
                if (skipOkPackets && message instanceof OSF2FDatagramOk) {
                    return;
                }
                conn1Incoming.add(message);
            }

            @Override
            public String toString() {
                return "1";
            }

            @Override
            public void initDatagramConnection() {
                OSF2FDatagramInit init1 = conn1.createInitMessage();
                conn2.initMessageReceived(init1);
            }

            @Override
            public boolean isLanLocal() {
                return false;
            }
        });
        manager2 = new MockDatagramConnectionManager("2");
        conn2Incoming = new LinkedList<Message>();
        conn2 = new DatagramConnection(manager2, new DatagramListener() {

            @Override
            public void sendDatagramOk(OSF2FDatagramOk osf2fDatagramOk) {
                conn1.okMessageReceived();
            }

            @Override
            public InetAddress getRemoteIp() {
                return localhost;
            }

            @Override
            public void datagramDecoded(Message message, int size) {
                if (skipOkPackets && message instanceof OSF2FDatagramOk) {
                    return;
                }
                conn2Incoming.add(message);
            }

            @Override
            public String toString() {
                return "2";
            }

            @Override
            public void initDatagramConnection() {
                OSF2FDatagramInit init2 = conn2.createInitMessage();
                conn1.initMessageReceived(init2);
            }

            @Override
            public boolean isLanLocal() {
                return false;
            }
        });

        // Send init packet from 1 to 2.
        conn1.reInitialize();
        // And from 2 to 1.
        conn2.reInitialize();

        // This should eventually result in an UDP packet getting sent from conn
        // 2 to conn 1.
        manager1.receive();

        // When using a real friend connection the init message must arrive
        // before the OK message, but in the test the ok message will arrive
        // before so we need to trigger a manual ok message since the first one
        // was dropped.
        conn1.sendUdpOK();
        manager2.receive();

        Assert.assertEquals(conn1.sendState, SendState.ACTIVE);
        Assert.assertEquals(conn2.sendState, SendState.ACTIVE);
        Assert.assertEquals(conn1.receiveState, ReceiveState.ACTIVE);
        Assert.assertEquals(conn2.receiveState, ReceiveState.ACTIVE);
    }

    @Test
    public void testSendReceiveSimple() throws Exception {
        byte[] testData = "hello".getBytes();
        OSF2FServiceDataMsg msg = createPacket(testData);
        conn1.sendMessage(msg);
        allocateRateLimitTokens(MAX_DATAGRAM_SIZE);
        manager2.receive();
        checkPacket(testData);
    }

    private void allocateRateLimitTokens(int amount) {
        manager1.rateLimiter.refillBucket(amount);
        manager1.rateLimiter.allocateTokens();
    }

    private void checkPacket(byte[] testData) {
        Assert.assertTrue(conn2Incoming.size() > 0, "No packets left.");
        OSF2FServiceDataMsg incoming = (OSF2FServiceDataMsg) conn2Incoming.removeFirst();
        byte[] inData = new byte[incoming.getPayload().remaining(SS)];
        incoming.getPayload().get(SS, inData);
        Assert.assertEquals(inData, testData);
        incoming.destroy();
    }

    private int sequenceNumber = 0;

    private OSF2FServiceDataMsg createPacket(byte[] testData) {
        return createPacket(testData, 0);
    }

    private OSF2FServiceDataMsg createPacket(byte[] testData, int channel) {
        DirectByteBuffer data = DirectByteBufferPool.getBuffer(AL, MAX_DATAGRAM_PAYLOAD_SIZE);
        data.put(SS, testData);
        data.flip(SS);
        return createPacket(data, channel);
    }

    private OSF2FServiceDataMsg createPacket(DirectByteBuffer data) {
        return createPacket(data, 0);
    }

    private OSF2FServiceDataMsg createPacket(DirectByteBuffer data, int channel) {
        OSF2FServiceDataMsg msg = new OSF2FServiceDataMsg((byte) 0, channel, -1, sequenceNumber++,
                (short) 1000, new int[0], data);
        return msg;
    }

    @Test
    public void testMultipleSimple() throws Exception {
        byte[] testData1 = "hello1".getBytes();
        OSF2FServiceDataMsg msg1 = createPacket(testData1);
        byte[] testData2 = "hello2".getBytes();
        OSF2FServiceDataMsg msg2 = createPacket(testData2);
        // Make sure that both are sent together.
        synchronized (conn1.encrypter) {
            conn1.sendMessage(msg1);
            conn1.sendMessage(msg2);
        }
        allocateRateLimitTokens(MAX_DATAGRAM_SIZE);
        // Only one call to receive (both messages must be in one packet).
        manager2.receive();
        checkPacket(testData1);
        checkPacket(testData2);
    }

    @Test
    public void testMultipleOverfull() throws Exception {
        // Queue up 3 packets, the last should not fit in the first datagram.
        int saveRoomFor = 2 * (OSF2FMessage.MESSAGE_HEADER_LEN + OSF2FServiceDataMsg.BASE_LENGTH) - 1;
        byte[] testData1 = new byte[MAX_DATAGRAM_PAYLOAD_SIZE - OSF2FServiceDataMsg.BASE_LENGTH
                - saveRoomFor];
        System.out.println(testData1.length);
        OSF2FServiceDataMsg msg1 = createPacket(testData1);
        OSF2FServiceDataMsg msg2 = createPacket(new byte[0]);
        OSF2FServiceDataMsg msg3 = createPacket(new byte[0]);
        // Make sure that all are sent together.
        synchronized (conn1.encrypter) {
            conn1.sendMessage(msg1);
            conn1.sendMessage(msg2);
            conn1.sendMessage(msg3);
        }
        allocateRateLimitTokens(2 * MAX_DATAGRAM_SIZE);
        // Only one call to receive (both messages must be in one packet).
        manager2.receive();
        checkPacket(testData1);
        checkPacket(new byte[0]);
        Assert.assertEquals(conn2Incoming.size(), 0);
        allocateRateLimitTokens(MAX_DATAGRAM_SIZE);
        manager2.receive();
        checkPacket(new byte[0]);
    }

    @Test
    public void testAllMinSize() throws Exception {
        // Make sure that all are sent together.
        skipOkPackets = false;
        int packets = MAX_DATAGRAM_PAYLOAD_SIZE / (OSF2FMessage.MESSAGE_HEADER_LEN) + 1 + 1;
        synchronized (conn1.sendThread.messageQueue) {
            for (int i = 0; i < packets; i++) {
                conn1.sendMessage(new OSF2FDatagramOk(0));
            }
        }
        // Only one call to receive (both messages must be in one packet).
        manager2.receive();

        Assert.assertEquals(conn2Incoming.size(), packets - 1);
        manager2.receive();
        Assert.assertEquals(conn2Incoming.size(), packets);
    }

    @Test
    public void testSocketChange() throws Exception {
        byte[] testData1 = "hello1".getBytes();
        OSF2FServiceDataMsg msg1 = createPacket(testData1);
        conn1.sendMessage(msg1);
        allocateRateLimitTokens(MAX_DATAGRAM_SIZE);
        manager2.receive();
        checkPacket(testData1);
        System.err.println("NEW CONN INIT START");
        manager1.socketUpdated();
        manager2.receive();

        System.err.println("NEW CONN INIT DONE");
        byte[] testData2 = "hello22".getBytes();
        OSF2FServiceDataMsg msg2 = createPacket(testData2);
        conn1.sendMessage(msg2);
        allocateRateLimitTokens(MAX_DATAGRAM_SIZE);
        manager2.receive();
        checkPacket(testData2);
    }

    /**
     * done: time=5.67s speed=23.53MB/s ,17.65kpps
     * 
     * @throws Exception
     */
    @Test
    public void testSendPerformance() throws Exception {
        logInfo(DatagramEncrypter.logger);
        logInfo(DatagramDecrypter.logger);
        logInfo(DatagramConnection.logger);
        logInfo(DatagramRateLimiter.logger);
        logInfo(DatagramRateLimitedChannelQueue.logger);

        long startTime = System.currentTimeMillis();
        final int packets = 200000;
        int length = MAX_CHANNEL_MESSAGE_PAYLOAD_SIZE;

        final int TOKEN_BATCH_SIZE = 1000;
        for (int i = 0; i < packets; i++) {
            DirectByteBuffer data = DirectByteBufferPool.getBuffer(AL, length);
            OSF2FMessage message = createPacket(data);
            conn1.sendMessage(message);
            if (i % TOKEN_BATCH_SIZE == 0) {
                allocateRateLimitTokens(TOKEN_BATCH_SIZE * MAX_DATAGRAM_SIZE);
            }
        }
        double mb = length * packets / (1024 * 1024.0);
        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
        while (conn1.queues.size() == 0
                || !((DatagramRateLimitedChannelQueue) conn1.queues.get(0)).isEmpty()) {
            Thread.sleep(10);
        }
        while (!conn1.sendThread.messageQueue.isEmpty()) {
            Thread.sleep(10);
        }
        System.out.println(String.format("done: time=%.2fs speed=%.2fMB/s ,%.2fkpps", elapsed, mb
                / elapsed, packets / elapsed / 1000));
    }

    /**
     * received 96.8 percent
     * done: time=12.77s speed=20.22MB/s ,15.66kpps
     * 
     * @throws Exception
     */
    @Test
    public void testReceivePerformance() throws Exception {
        logInfo(DatagramEncrypter.logger);
        logInfo(DatagramDecrypter.logger);
        logInfo(DatagramConnection.logger);
        logInfo(DatagramRateLimiter.logger);
        logInfo(DatagramRateLimitedChannelQueue.logger);

        long startTime = System.currentTimeMillis();
        final int packets = 200000;
        final int length = MAX_CHANNEL_MESSAGE_PAYLOAD_SIZE;
        final int TOKEN_BATCH_SIZE = 1000;

        Thread sendThread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < packets; i++) {
                    DirectByteBuffer data = DirectByteBufferPool.getBuffer(AL, length);
                    OSF2FMessage message = createPacket(data);
                    conn1.sendMessage(message);
                    if (i % TOKEN_BATCH_SIZE == 0) {
                        allocateRateLimitTokens(TOKEN_BATCH_SIZE * MAX_DATAGRAM_SIZE);
                    }
                }
            }
        });
        sendThread.start();

        int packet = 0;
        try {
            for (; packet < packets; packet++) {
                manager2.receive();
                conn2Incoming.removeFirst().destroy();
            }
        } catch (java.net.SocketTimeoutException e) {
            // Expected
        }
        double mb = length * packet / (1024 * 1024.0);
        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
        double percentReceived = packet * 100.0 / packets;
        System.out.println(String.format("received %.1f percent", percentReceived));
        System.out.println(String.format("done: time=%.2fs speed=%.2fMB/s ,%.2fkpps", elapsed, mb
                / elapsed, packets / elapsed / 1000));

        Assert.assertTrue(percentReceived > 0.8,
                String.format("high packet loss (%.1f) percent", percentReceived));
    }

    @Test
    public void testChannelRateLimitSimple() throws Exception {
        byte[] testData1 = "hello1".getBytes();
        byte[] testData2 = "hello22".getBytes();
        OSF2FServiceDataMsg msg1 = createPacket(testData1, 1);

        // Queue 2 packets, channel 1 before 2.
        conn1.sendMessage(msg1);
        OSF2FServiceDataMsg msg2 = createPacket(testData2, 2);
        conn1.sendMessage(msg2);
        // Give tokens to channel 2 first.
        conn1.queueMap.get(2).refillBucket(MAX_DATAGRAM_SIZE);
        // The give tokens to channel 1.
        conn1.queueMap.get(1).refillBucket(MAX_DATAGRAM_SIZE);

        // The packet on channel 2 should arrive first.
        manager2.receive();
        checkPacket(testData2);
        manager2.receive();
        checkPacket(testData1);

        // Check that it won't eat tokens if full.
        conn1.setTokenBucketSize(MAX_DATAGRAM_SIZE);
        conn1.refillBucket(MAX_DATAGRAM_SIZE);

        // No more tokens should be added.
        assertEquals(conn1.refillBucket(1), 0);
        conn1.allocateTokens();
        conn1.refillBucket(MAX_DATAGRAM_SIZE);
        assertEquals(conn1.refillBucket(1), 0);
    }

    @Test
    public void testChannelFairSharing() throws Exception {
        int PACKET_NUM = 100;

        // Queue 2 packets to create channel 0 and 1
        conn1.sendMessage(createPacket(new byte[MAX_CHANNEL_MESSAGE_PAYLOAD_SIZE], 0));
        conn1.sendMessage(createPacket(new byte[MAX_CHANNEL_MESSAGE_PAYLOAD_SIZE], 1));
        allocateRateLimitTokens(2 * MAX_DATAGRAM_SIZE);
        checkPacketChannel(0);
        checkPacketChannel(1);

        DatagramRateLimitedChannelQueue channel0RateLimiter = conn1.queueMap.get(0);
        DatagramRateLimitedChannelQueue channel1RateLimiter = conn1.queueMap.get(1);

        // After the queues are created we can change parameters.

        // Increase max queue size.
        channel0RateLimiter.maxQueueLength = PACKET_NUM * MAX_DATAGRAM_SIZE;
        channel1RateLimiter.maxQueueLength = PACKET_NUM * MAX_DATAGRAM_SIZE;

        // Fill channel 0 followed by channel 1.
        for (int i = 0; i < PACKET_NUM; i++) {
            if (i < PACKET_NUM / 2) {
                conn1.sendMessage(createPacket(new byte[MAX_CHANNEL_MESSAGE_PAYLOAD_SIZE], 0));
            } else {
                conn1.sendMessage(createPacket(new byte[MAX_CHANNEL_MESSAGE_PAYLOAD_SIZE], 1));
            }
        }

        // Packets should arrive interleaved.
        for (int i = 0; i < PACKET_NUM; i++) {
            if (i % 2 == 0) {
                allocateRateLimitTokens(2 * MAX_DATAGRAM_SIZE);
            }
            if (!checkPacketChannel(i % 2)) {
                String msg = "Error at packet " + i + ", expected " + i % 2;
                System.err.println(msg);
                Assert.fail(msg);
            }
        }
    }

    private boolean checkPacketChannel(int channelId) throws IOException {
        manager2.receive();
        OSF2FServiceDataMsg incoming = (OSF2FServiceDataMsg) conn2Incoming.removeFirst();
        return incoming.getChannelId() == channelId;
    }

    /** Boilerplate code for running as executable. */
    public static void main(String[] args) throws Exception {
        TestUtils.swtCompatibleTestRunner(DatagramConnectionTest.class);
    }
}
