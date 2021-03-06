package edu.washington.cs.oneswarm.f2f.servicesharing;

import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.aelitis.azureus.core.networkmanager.IncomingMessageQueue.MessageQueueListener;
import com.aelitis.azureus.core.networkmanager.NetworkConnection;
import com.aelitis.azureus.core.networkmanager.NetworkConnection.ConnectionListener;
import com.aelitis.azureus.core.peermanager.messaging.Message;

import edu.washington.cs.oneswarm.f2f.network.LowLatencyMessageWriter;

public class ServiceSharingLoopback {
    public final static Logger logger = Logger.getLogger(ServiceSharingLoopback.class.getName());

    private final NetworkConnection incomingConnection;
    private final SharedService sharedService;
    private NetworkConnection serviceConnection;

    public ServiceSharingLoopback(SharedService sharedService, NetworkConnection incomingConnection) {
        this.sharedService = sharedService;
        this.incomingConnection = incomingConnection;
    }

    public void connect() {
        serviceConnection = sharedService.createConnection();
        logger.info("forwarding connection to service: " + sharedService + " address="
                + serviceConnection.getEndpoint().getNotionalAddress());
        incomingConnection.connect(true, new ServiceConnectionListener(false));
        serviceConnection.connect(true, new ServiceConnectionListener(true));
    }

    public void close() {
        incomingConnection.close();
        serviceConnection.close();
        logger.info("closed loopback connection");
    }

    private class ServiceConnectionListener implements ConnectionListener {
        private final boolean outgoing;

        public ServiceConnectionListener(boolean outgoing) {
            this.outgoing = outgoing;
        }

        @Override
        public void connectFailure(Throwable failure_msg) {
            logger.info("incoming: connect failure to " + sharedService + " "
                    + failure_msg.getClass().getName() + "::" + failure_msg.getMessage());
        }

        @Override
        public void connectStarted() {
            logger.info("connect started to " + sharedService);
        }

        @Override
        public void connectSuccess(ByteBuffer remaining_initial_data) {
            logger.info("connect success to " + sharedService);
            if (outgoing) {
                startMessageProcessing();
            }
        }

        @Override
        public void exceptionThrown(Throwable error) {
            logger.info("Exception in " + sharedService + " " + error.getClass().getName() + "::"
                    + error.getMessage());
            close();
        }

        @Override
        public String getDescription() {
            return "loopback connect listener";
        }

    }

    private void startMessageProcessing() {
        incomingConnection.getIncomingMessageQueue().registerQueueListener(
                new MessageQueueListener() {

                    @Override
                    public void dataBytesReceived(int byte_count) {
                    }

                    @Override
                    public boolean messageReceived(Message message) {
                        if (logger.isLoggable(Level.FINEST)) {
                            logger.finest("From client: " + message.getDescription());
                        }
                        transferMessage(serviceConnection, (DataMessage) message);
                        return true;
                    }

                    @Override
                    public void protocolBytesReceived(int byte_count) {
                    }
                });
        serviceConnection.getIncomingMessageQueue().registerQueueListener(
                new MessageQueueListener() {
                    @Override
                    public void dataBytesReceived(int byte_count) {
                    }

                    @Override
                    public boolean messageReceived(Message message) {
                        if (logger.isLoggable(Level.FINEST)) {
                            logger.finest("From server: " + message.getDescription());
                        }
                        transferMessage(incomingConnection, (DataMessage) message);
                        return true;
                    }

                    @Override
                    public void protocolBytesReceived(int byte_count) {
                    }
                });
        serviceConnection.startMessageProcessing();
        incomingConnection.startMessageProcessing();
        serviceConnection.enableEnhancedMessageProcessing(true);
        serviceConnection.getOutgoingMessageQueue().registerQueueListener(
                new LowLatencyMessageWriter(serviceConnection));

        incomingConnection.enableEnhancedMessageProcessing(true);
        incomingConnection.getOutgoingMessageQueue().registerQueueListener(
                new LowLatencyMessageWriter(incomingConnection));

    }

    private void transferMessage(NetworkConnection target, DataMessage message) {
        target.getOutgoingMessageQueue().addMessage(new DataMessage(message.transferPayload()),
                false);
    }
}
