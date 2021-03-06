package edu.washington.cs.oneswarm.f2f.servicesharing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gudy.azureus2.core3.config.COConfigurationManager;
import org.gudy.azureus2.core3.util.DirectByteBuffer;

import com.aelitis.azureus.core.networkmanager.IncomingMessageQueue.MessageQueueListener;
import com.aelitis.azureus.core.networkmanager.NetworkConnection;
import com.aelitis.azureus.core.networkmanager.NetworkConnection.ConnectionListener;
import com.aelitis.azureus.core.networkmanager.NetworkManager;
import com.aelitis.azureus.core.networkmanager.Transport;
import com.aelitis.azureus.core.networkmanager.impl.RateHandler;
import com.aelitis.azureus.core.networkmanager.impl.tcp.TCPTransportImpl;
import com.aelitis.azureus.core.peermanager.messaging.Message;

import edu.washington.cs.oneswarm.f2f.network.LowLatencyMessageWriter;

public class ServiceConnection implements ServiceChannelEndpointDelegate {
    public static final Logger logger = Logger.getLogger(ServiceConnection.class.getName());
    private static final byte ss = 97;

    static final String SERVICE_PRIORITY_KEY = "SERVICE_CLIENT_MULTIPLEX_QUEUE";
    static final int SERVICE_MSG_BUFFER_SIZE = 1024 * COConfigurationManager.getIntParameter(
            "SERVICE_CLIENT_flow", 10);

    protected final int MAX_CHANNELS = COConfigurationManager.getIntParameter(
            "SERVICE_CLIENT_channels", 4);
    protected final EnumSet<ServiceFeatures> FEATURES;

    private class BufferedMessage {
        public BufferedMessage(DirectByteBuffer msg, SequenceNumber msgId) {
            this.messageId = msgId;
            this.message = msg;
        }

        public SequenceNumber messageId;
        public DirectByteBuffer message;
    };

    enum ServiceFeatures {
        UDP, PACKET_DUPLICATION, ADAPTIVE_DUPLICATION
    };

    protected final MessageStreamMultiplexer mmt;
    protected final LinkedList<BufferedMessage> bufferedNetworkMessages = new LinkedList<BufferedMessage>();
    protected final DirectByteBuffer[] bufferedServiceMessages = new DirectByteBuffer[SERVICE_MSG_BUFFER_SIZE];
    protected final List<ServiceChannelEndpoint> networkChannels = Collections
            .synchronizedList(new ArrayList<ServiceChannelEndpoint>());
    protected boolean networkChannelEOF;
    protected final NetworkConnection serviceChannel;
    protected boolean serviceChannelConnected;
    protected boolean serviceChannelEOF;
    protected final boolean isOutgoing;
    protected final short subchannelId;
    protected int serviceSequenceNumber;
    protected int windowSize = COConfigurationManager.getIntParameter("SERVICE_CLIENT_channels", 4);
    private final long searchKey;

    public ServiceConnection(boolean outgoing, short subchannel, long searchKey,
            final NetworkConnection serviceChannel) {
        this.serviceSequenceNumber = 0;
        this.networkChannelEOF = false;
        this.isOutgoing = outgoing;
        this.subchannelId = subchannel;
        this.serviceChannel = serviceChannel;
        this.serviceChannelConnected = serviceChannel.isConnected();
        this.serviceChannelEOF = false;
        if (this.serviceChannelConnected) {
            logger.info("Service connection created to pre-connected service channel.");
            // Add our connection listener.
            connectServiceChannel();
        }
        this.mmt = new MessageStreamMultiplexer(subchannel);
        this.searchKey = searchKey;

        // Load Configuration.
        ArrayList<ServiceFeatures> features = new ArrayList<ServiceFeatures>();
        if (COConfigurationManager.getBooleanParameter("SERVICE_CLIENT_udp")) {
            features.add(ServiceFeatures.UDP);
        }
        if (COConfigurationManager.getBooleanParameter("SERVICE_CLIENT_duplication")) {
            features.add(ServiceFeatures.PACKET_DUPLICATION);
        }
        if (COConfigurationManager.getBooleanParameter("SERVICE_CLIENT_adaptive")) {
            features.add(ServiceFeatures.ADAPTIVE_DUPLICATION);
        }
        this.FEATURES = EnumSet.copyOf(features);
        logger.info("Service Connection active with settings: start window = "
                + (windowSize / 1024)
                + ", flow="
                + (SERVICE_MSG_BUFFER_SIZE / 1024)
                + ", max="
                + MAX_CHANNELS
                + ", "
                + (features.contains(ServiceFeatures.UDP) ? "UDP" : "No UDP")
                + ", "
                + (features.contains(ServiceFeatures.PACKET_DUPLICATION) ? "Duplication"
                        : "No Duplication")
                + ", "
                + (features.contains(ServiceFeatures.ADAPTIVE_DUPLICATION) ? "Adaptive"
                        : "Not Adapitive"));
    }

    public boolean isOutgoing() {
        return isOutgoing;
    }

    public boolean addChannel(ServiceChannelEndpoint channel) {
        if (this.networkChannels.size() >= MAX_CHANNELS) {
            return false;
        }
        this.networkChannels.add(channel);
        this.mmt.addChannel(channel);
        channel.addDelegate(this, this.subchannelId);

        if (!serviceChannelConnected) {
            connectServiceChannel();
        }
        return true;
    }

    public long getSearchKey() {
        return this.searchKey;
    }

    private void connectServiceChannel() {
        serviceChannelConnected = true;
        serviceChannel.connect(true, new ConnectionListener() {
            @Override
            public void connectFailure(Throwable failure_msg) {
                logger.fine(ServiceConnection.this.getDescription()
                        + ": connection failure to service.");
                ServiceConnection.this.close("Exception during connect");
            }

            @Override
            public void connectStarted() {
                logger.fine(ServiceConnection.this.getDescription()
                        + ": Service connection initiated.");
            }

            @Override
            public void connectSuccess(ByteBuffer remaining_initial_data) {
                logger.fine(ServiceConnection.this.getDescription()
                        + ": Service connection established.");
                serviceChannel.getIncomingMessageQueue().registerQueueListener(
                        new ServerIncomingMessageListener());
                serviceChannel.startMessageProcessing();
                NetworkManager.getSingleton().upgradeTransferProcessing(serviceChannel,
                        new ServiceRateHandler(ServiceConnection.this));
                serviceChannel.getOutgoingMessageQueue().registerQueueListener(
                        new LowLatencyMessageWriter(serviceChannel));
                flushServiceQueue();
            }

            @Override
            public void exceptionThrown(Throwable error) {
                if (error != null && error.getMessage() != null
                        && error.getMessage().indexOf("end of stream on socket read") != -1) {
                    // End of input stream will be communicated as 0 length
                    // message - but we shouldn't close.
                    if (networkChannelEOF) {
                        ServiceConnection.this.close("End of Service stream.");
                    }
                    if (!serviceChannelEOF) {
                        logger.info("End of service channel input stream.");
                        serviceChannelEOF = true;
                        ServiceConnection.this.routeMessageToChannel(
                            new DirectByteBuffer(ByteBuffer.allocate(0)), null);
                    }
                    if (!serviceChannel.isConnected()) {
                        ServiceConnection.this.close("Service Channel Closed");
                    }
                } else {
                    ServiceConnection.this.close("Exception from Service channel:"
                            + error.getMessage());
                }
            }

            @Override
            public String getDescription() {
                return ServiceConnection.this.getDescription() + ": Service Channel Observer.";
            }
        });
    }

    public String getDescription() {
        String destination = "[unknown]";
        if (this.networkChannels.size() > 0) {
            destination = "" + this.networkChannels.get(0).getServiceKey();
        }
        return "Service connection " + this.subchannelId + " to " + destination
                + " over " + this.networkChannels.size() + " channels";
    }

    public void close(String reason) {
        logger.info("Service Connection closed: " + reason);

        ChannelBufferInfo b = new ChannelBufferInfo();
        List<ServiceChannelEndpoint> openChannels = getAvailableChannels(null, b);

        ServiceChannelEndpoint[] channels = this.networkChannels.toArray(new ServiceChannelEndpoint[0]);
        this.networkChannels.clear();
        if (openChannels.size() > 0 && !networkChannelEOF) {
            // Send RST Packet.
            openChannels.get(0).writeMessage(mmt.nextMsg(), null,
                    FEATURES.contains(ServiceFeatures.UDP));
        }
        for (ServiceChannelEndpoint conn : channels) {
            conn.removeDelegate(this);
        }
        this.serviceChannel.close();

        synchronized (bufferedServiceMessages) {
            for (int i = 0; i < SERVICE_MSG_BUFFER_SIZE; i++) {
                if (bufferedServiceMessages[i] != null) {
                    bufferedServiceMessages[i].returnToPool();
                }
            }
        }

        synchronized (bufferedNetworkMessages) {
            bufferedNetworkMessages.clear();
        }
    }

    public void closeUponReading(int sequenceNumber) {
        synchronized (bufferedServiceMessages) {
            if (sequenceNumber >= serviceSequenceNumber + SERVICE_MSG_BUFFER_SIZE) {
                // Throw out to prevent buffer overflow.
                logger.warning("RST message dropped, exceeded message buffer.");
            } else {
                // Mark service channel as done, since RST implies no more
                // messages can be sent.
                this.serviceChannelEOF = true;
                bufferedServiceMessages[sequenceNumber & (SERVICE_MSG_BUFFER_SIZE - 1)] = new DirectByteBuffer(
                        ByteBuffer.allocate(0));
            }
        }
        flushServiceQueue();
    }

    public long getBytesIn() {
        long in = 0;
        synchronized (this.networkChannels) {
            for (ServiceChannelEndpoint conn : this.networkChannels) {
                in += conn.getBytesIn();
            }
        }
        return in;
    }

    public long getBytesOut() {
        long out = 0;
        synchronized (this.networkChannels) {
            for (ServiceChannelEndpoint conn : this.networkChannels) {
                out += conn.getBytesOut();
            }
        }
        return out;
    }

    public int getDownloadRate() {
        int rate = 0;
        synchronized (this.networkChannels) {
            for (ServiceChannelEndpoint conn : this.networkChannels) {
                rate += conn.getDownloadRate();
            }
        }
        return rate;
    }

    public int getUploadRate() {
        int rate = 0;
        synchronized (this.networkChannels) {
            for (ServiceChannelEndpoint conn : this.networkChannels) {
                rate += conn.getUploadRate();
            }
        }
        return rate;
    }

    public int[] getChannelIds() {
        int[] channels;
        synchronized (this.networkChannels) {
            channels = new int[this.networkChannels.size()];
            int i = 0;
            for (ServiceChannelEndpoint conn : this.networkChannels) {
                channels[i++] = conn.getChannelId();
            }
        }
        return channels;
    }

    public long getLastMsgTime() {
        long time = 0;
        synchronized (this.networkChannels) {
            for (ServiceChannelEndpoint conn : this.networkChannels) {
                time = Math.max(time, conn.getLastMsgTime());
            }
        }
        return time;
    }

    @Override
    public void channelDidConnect(ServiceChannelEndpoint sender) {
        if (serviceChannel.isConnected()) {
            NetworkManager.getSingleton().downgradeTransferProcessing(serviceChannel);
            NetworkManager.getSingleton().upgradeTransferProcessing(serviceChannel,
                    new ServiceRateHandler(ServiceConnection.this));
        }
    }

    @Override
    public boolean channelGotMessage(ServiceChannelEndpoint sender, OSF2FServiceDataMsg msg) {
        if (msg.getSubchannel() != this.subchannelId) {
            return false;
        }

        if (msg.isAck()) {
            logger.fine("Acked msg " + msg.getSequenceNumber());
            windowSize += mmt.onAck(msg);
            return true;
        }

        synchronized (bufferedServiceMessages) {
            if (msg.getSequenceNumber() >= serviceSequenceNumber + SERVICE_MSG_BUFFER_SIZE) {
                // Throw out to prevent buffer overflow.
                logger.warning("Incoming service message dropped, exceeded message buffer.");
                return true;
            } else if (msg.getSequenceNumber() < serviceSequenceNumber) {
                logger.info("Incoming service message dropped, already processed.");
                return true;
            } else {
                DirectByteBuffer payload = msg.transferPayload();
                bufferedServiceMessages[msg.getSequenceNumber() & (SERVICE_MSG_BUFFER_SIZE - 1)] = payload;
            }
        }
        flushServiceQueue();
        // If message is Rst, mark it as unhandled so that it is also given to
        // the ServiceConnectionManager to initiate teardown.
        return !msg.isRst();
    }

    private void flushServiceQueue() {
        if (!serviceChannel.isConnected()) {
            return;
        }
        synchronized (bufferedServiceMessages) {
            while (bufferedServiceMessages[serviceSequenceNumber & (SERVICE_MSG_BUFFER_SIZE - 1)] != null) {
                DirectByteBuffer buf = bufferedServiceMessages[serviceSequenceNumber
                        & (SERVICE_MSG_BUFFER_SIZE - 1)];
                if (buf.remaining(ss) == 0) {
                    if (!networkChannelEOF) {
                        networkChannelEOF = true;
                        logger.fine("Writing EOF to service");
                        Transport t = serviceChannel.getTransport();
                        if (t.isTCP()) {
                            TCPTransportImpl tcpTransport = (TCPTransportImpl) t;
                            try {
                                tcpTransport.getSocketChannel().socket().getOutputStream().close();
                            } catch (IOException e) {
                                logger.warning("Could not close outbound service connection.");
                            }
                        }
                    }
                    if (serviceChannelEOF) {
                        this.close("Reached end of stream.");
                        return;
                    }
                    return;
                }
                /*
                 * int pos = buf.position((byte) 0);
                 * int len = buf.remaining((byte) 0);
                 * byte[] temp = new byte[len];
                 * buf.get((byte) 0, temp);
                 * for (int i = 0; i < len; i++) {
                 * if (temp[i] == 0) {
                 * temp[i] = (byte) 0xff;
                 * }
                 * }
                 * System.out.println("!!!MSG to Service: " + new String(temp));
                 * buf.position((byte) 0, pos);
                 */
                DataMessage outgoing = new DataMessage(buf);
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest("writing message to service queue: " + outgoing.getDescription());
                }
                serviceChannel.getOutgoingMessageQueue().addMessage(outgoing, false);
                bufferedServiceMessages[serviceSequenceNumber & (SERVICE_MSG_BUFFER_SIZE - 1)] = null;
                serviceSequenceNumber++;
            }
        }
    }

    @Override
    public void channelIsReady(ServiceChannelEndpoint channel) {
        if (channel != null && !this.networkChannels.contains(channel)) {
            logger.warning("Unregistered channel attempted to provide service transit.");
            return;
        }

        synchronized (bufferedNetworkMessages) {
            int size = bufferedNetworkMessages.size();
            while (size > 0) {
                BufferedMessage b = bufferedNetworkMessages.pop();
                if (!b.messageId.isAcked()) {
                    routeMessageToChannel(b.message, b.messageId);
                }
                if (bufferedNetworkMessages.size() == size) {
                    break;
                }
                size = bufferedNetworkMessages.size();
            }
        }
    }

    @Override
    public void channelDidClose(ServiceChannelEndpoint channel) {
        if (!this.networkChannels.contains(channel)) {
            return;
        }

        if (mmt.hasOutstanding(channel)) {
            synchronized (bufferedNetworkMessages) {
                for (Map.Entry<SequenceNumber, DirectByteBuffer> e : mmt.getOutstanding(channel)
                        .entrySet()) {
                    bufferedNetworkMessages.add(new BufferedMessage(e.getValue(), e.getKey()));
                }
            }
        }

        mmt.removeChannel(channel);
        this.networkChannels.remove(channel);
        if (this.networkChannels.size() == 0) {
            logger.info("All channels removed. closing service connection.");
            close("No Channels Remaining.");
        }
        channelIsReady(null);
    }

    @Override
    public boolean writesMessages() {
        return true;
    }

    private int getAvailableBytes() {
        ChannelBufferInfo b = new ChannelBufferInfo();
        getAvailableChannels(null, b);
        if (b.replication == 0) {
            return 0;
        }
        return b.capacity / b.replication;
    }

    private class ChannelBufferInfo {
        int capacity = 0;
        int potential = 0;
        int replication = 0;
    };

    private List<ServiceChannelEndpoint> getAvailableChannels(SequenceNumber msgId,
            ChannelBufferInfo b) {
        List<ServiceChannelEndpoint> channels = new ArrayList<ServiceChannelEndpoint>();

        b.capacity = 0;
        b.potential = 0;

        synchronized (this.networkChannels) {
            for (ServiceChannelEndpoint c : networkChannels) {
                // Don't allow questionable paths to be opened by the
                // server.
                if (!c.isStarted() && !c.isOutgoing()) {
                    continue;
                }

                b.capacity += c.getWriteCapacity(this);
                b.potential += c.getPotentialWriteCapacity();
                // Don't allow full paths to get greedy.
                if (c.isStarted() && c.getWriteCapacity(this) == 0) {
                    continue;
                }

                // Don't resend on an active channel.
                if (msgId != null && msgId.getChannels().contains(new Integer(c.getChannelId()))) {
                    continue;
                }

                // Decide on priority.
                if (c.isStarted()) {
                    boolean added = false;
                    for (int i = 0; i < channels.size(); i++) {
                        ServiceChannelEndpoint current = channels.get(i);
                        if (!current.isStarted()) {
                            channels.add(i, c);
                            added = true;
                            break;
                        } else if (c.getBytesOut() / c.getAge() > current.getBytesOut()
                                / current.getAge()) {
                            channels.add(i, c);
                            added = true;
                            break;
                        }
                    }
                    if (!added) {
                        channels.add(c);
                    }
                } else {
                    channels.add(c);
                }
            }
        }

        if (this.FEATURES == null || !this.FEATURES.contains(ServiceFeatures.PACKET_DUPLICATION)) {
            b.replication = 1;
        } else if (this.FEATURES.contains(ServiceFeatures.ADAPTIVE_DUPLICATION)) {
            if (b.capacity == 0) {
                b.replication = channels.size();
            } else {
                float replicationFactor = (float) (b.capacity * 1.0 / b.potential);
                int replicas = (int) (replicationFactor * channels.size());
                if (msgId != null) {
                    replicas -= msgId.getChannels().size();
                }
                if (replicas > channels.size()) {
                    replicas = channels.size();
                }
                if (replicas < 1) {
                    replicas = 1;
                }
                b.replication = replicas;
            }
        } else {
            b.replication = channels.size();
        }

        return channels;
    }

    /**
     * Route a message from the service to appropriate network channel(s).
     * 
     * @param msg
     *            The message to route.
     * @param msgId
     *            The sequence number of the msg if determined, or null.
     * @return Whether the msg was handled.
     */
    boolean routeMessageToChannel(DirectByteBuffer msg, SequenceNumber msgId) {
        ChannelBufferInfo b = new ChannelBufferInfo();
        List<ServiceChannelEndpoint> channels = getAvailableChannels(msgId, b);
        if (channels.size() == 0) {
            logger.info("Currently advertising " + b.capacity + " available buffer");
            logger.warning("not accepting more data from service, no available channel.");
            return false;
        }

        ArrayList<ServiceChannelEndpoint> channelsToUse = new ArrayList<ServiceChannelEndpoint>();
        for (int i = 0; i < b.replication; i++) {
            ServiceChannelEndpoint sce = channels.get(i);
            if (sce != null) {
                channelsToUse.add(sce);
            }
        }

        if (msgId == null) {
            msgId = mmt.nextMsg();
        }
        ArrayList<DirectByteBuffer> msgcpys = new ArrayList<DirectByteBuffer>();
        msgcpys.add(msg);
        while (msgcpys.size() < channelsToUse.size()) {
            ByteBuffer cpy = msg.getBuffer(ss).asReadOnlyBuffer();
            msgcpys.add(new DirectByteBuffer(cpy));
        }
        logger.finest("Message will attempt to send with replication " + channelsToUse.size());
        for (ServiceChannelEndpoint c : channelsToUse) {
            msg = msgcpys.remove(0);
            if (!c.isStarted()) {
                logger.finest("Unstarted channel chosen, msg buffered");
                synchronized (bufferedNetworkMessages) {
                    if (bufferedNetworkMessages.size() < SERVICE_MSG_BUFFER_SIZE) {
                        bufferedNetworkMessages.add(new BufferedMessage(msg, msgId));
                    }
                }
            } else {
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest("Writing message to channel: " + c.getDescription());
                }
                mmt.sendMsg(msgId, c);
                c.writeMessage(msgId, msg, FEATURES.contains(ServiceFeatures.UDP));
            }
        }
        if (msgId.getChannels().size() == 0) {
            return false;
        } else {
            return true;
        }
    }

    protected class ServerIncomingMessageListener implements MessageQueueListener {

        @Override
        public void dataBytesReceived(int byte_count) {
        }

        @Override
        public boolean messageReceived(Message message) {
            logger.finest(ServiceConnection.this.getDescription() + ": Service message recieved.");

            if (!(message instanceof DataMessage)) {
                String msg = "got wrong message type from the service: ";
                logger.warning(msg + message.getDescription());
                ServiceConnection.this.close(msg);
                return false;
            }
            DataMessage dataMessage = (DataMessage) message;
            boolean routed = ServiceConnection.this.routeMessageToChannel(
                    dataMessage.transferPayload(), null);
            if (!routed) {
                logger.warning("No channel accepted incoming packet.");
            }
            return true;
        }

        @Override
        public void protocolBytesReceived(int byte_count) {
        }
    }

    protected class ServiceRateHandler implements RateHandler {
        private final ServiceConnection connection;

        ServiceRateHandler(ServiceConnection c) {
            this.connection = c;
        }

        @Override
        public int getCurrentNumBytesAllowed() {
            return this.connection.getAvailableBytes();
        }

        @Override
        public void bytesProcessed(int num_bytes_processed) {
            return;
        }

    }
}