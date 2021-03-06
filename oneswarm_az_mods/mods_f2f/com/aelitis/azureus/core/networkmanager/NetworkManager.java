/*
 * Created on Jul 29, 2004
 * Created by Alon Rohter
 * Copyright (C) 2004, 2005, 2006 Aelitis, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * AELITIS, SAS au capital de 46,603.30 euros
 * 8 Allee Lenotre, La Grille Royale, 78600 Le Mesnil le Roi, France.
 *
 */

package com.aelitis.azureus.core.networkmanager;

import java.nio.ByteBuffer;

import org.gudy.azureus2.core3.config.COConfigurationManager;
import org.gudy.azureus2.core3.config.ParameterListener;
import org.gudy.azureus2.core3.download.DownloadManager;
import org.gudy.azureus2.core3.global.GlobalManagerListener;
import org.gudy.azureus2.core3.util.Debug;

import com.aelitis.azureus.core.AzureusCoreFactory;
import com.aelitis.azureus.core.networkmanager.impl.IncomingConnectionManager;
import com.aelitis.azureus.core.networkmanager.impl.RateControlledEntity;
import com.aelitis.azureus.core.networkmanager.impl.RateHandler;
import com.aelitis.azureus.core.networkmanager.impl.ReadController;
import com.aelitis.azureus.core.networkmanager.impl.TransferProcessor;
import com.aelitis.azureus.core.networkmanager.impl.TransportHelper;
import com.aelitis.azureus.core.networkmanager.impl.WriteController;
import com.aelitis.azureus.core.networkmanager.impl.WriteEventListener;
import com.aelitis.azureus.core.networkmanager.impl.http.HTTPNetworkManager;
import com.aelitis.azureus.core.networkmanager.impl.tcp.TCPNetworkManager;
import com.aelitis.azureus.core.networkmanager.impl.udp.UDPNetworkManager;
import com.aelitis.azureus.core.peermanager.messaging.MessageStreamDecoder;
import com.aelitis.azureus.core.peermanager.messaging.MessageStreamEncoder;
import com.aelitis.azureus.core.peermanager.messaging.MessageStreamFactory;
import com.aelitis.azureus.core.util.FeatureAvailability;



/**
 *
 */
public class NetworkManager {
	//**********************************************************
  // edit by isdal: don't count f2f traffic twice, ignore it if the transport description has this prefix
  public static final String OSF2F_TRANSPORT_PREFIX = "OSF2f_OverlayTransport";
  //**********************************************************
 
  public static final int UNLIMITED_RATE = 1024 * 1024 * 100; //100 mbyte/s
    
  private static final NetworkManager instance = new NetworkManager();

  private static int max_download_rate_bps;
  private static int external_max_download_rate_bps;
  
  private static int max_upload_rate_bps_normal;
  private static int max_upload_rate_bps_seeding_only;
  private static int max_upload_rate_bps;
  
  private static boolean lan_rate_enabled;
  private static int max_lan_upload_rate_bps;
  private static int max_lan_download_rate_bps;
  
  private static boolean seeding_only_mode_allowed;
  private static boolean seeding_only_mode = false;  
  
  public static boolean 	REQUIRE_CRYPTO_HANDSHAKE;
  public static boolean 	INCOMING_HANDSHAKE_FALLBACK_ALLOWED;	
  public static boolean 	OUTGOING_HANDSHAKE_FALLBACK_ALLOWED;
  
  private static boolean	USE_REQUEST_LIMITING;
	

  static {
  	COConfigurationManager.addAndFireParameterListeners(
  			new String[]{ "network.transport.encrypted.require",
    									"network.transport.encrypted.fallback.incoming",
    									"network.transport.encrypted.fallback.outgoing",
    									"LAN Speed Enabled",
    									"Max Upload Speed KBs",
    									"Max LAN Upload Speed KBs",
    									"Max Upload Speed Seeding KBs",
    									"enable.seedingonly.upload.rate",
    									"Max Download Speed KBs",
    									"Max LAN Download Speed KBs",
    									"network.tcp.mtu.size",
  										"network.udp.mtu.size",
  										"Use Request Limiting"},
  										
    		new ParameterListener()	{
    			 @Override
                public void  parameterChanged(	String ignore ) {
    				 REQUIRE_CRYPTO_HANDSHAKE				= COConfigurationManager.getBooleanParameter("network.transport.encrypted.require");
    				 INCOMING_HANDSHAKE_FALLBACK_ALLOWED	= COConfigurationManager.getBooleanParameter("network.transport.encrypted.fallback.incoming");
    				 OUTGOING_HANDSHAKE_FALLBACK_ALLOWED	= COConfigurationManager.getBooleanParameter("network.transport.encrypted.fallback.outgoing");
  
    				 USE_REQUEST_LIMITING					= COConfigurationManager.getBooleanParameter("Use Request Limiting");
    				 
    				 max_upload_rate_bps_normal = COConfigurationManager.getIntParameter( "Max Upload Speed KBs" ) * 1024;
    				 if( max_upload_rate_bps_normal < 1024 )  max_upload_rate_bps_normal = UNLIMITED_RATE;
    				 if( max_upload_rate_bps_normal > UNLIMITED_RATE )  max_upload_rate_bps_normal = UNLIMITED_RATE;
    				 
    				 max_lan_upload_rate_bps = COConfigurationManager.getIntParameter( "Max LAN Upload Speed KBs" ) * 1024;
    				 if( max_lan_upload_rate_bps < 1024 )  max_lan_upload_rate_bps = UNLIMITED_RATE;
    				 if( max_lan_upload_rate_bps > UNLIMITED_RATE )  max_lan_upload_rate_bps = UNLIMITED_RATE;
    	        
    				  
    				 max_upload_rate_bps_seeding_only = COConfigurationManager.getIntParameter( "Max Upload Speed Seeding KBs" ) * 1024; 
    				 if( max_upload_rate_bps_seeding_only < 1024 )  max_upload_rate_bps_seeding_only = UNLIMITED_RATE;
    				 if( max_upload_rate_bps_seeding_only > UNLIMITED_RATE )  max_upload_rate_bps_seeding_only = UNLIMITED_RATE;
    	        
    				 seeding_only_mode_allowed = COConfigurationManager.getBooleanParameter( "enable.seedingonly.upload.rate" );
    			
    				 
    				 external_max_download_rate_bps = max_download_rate_bps = (COConfigurationManager.getIntParameter( "Max Download Speed KBs" ) * 1024); // leave 5KiB/s room for the request limiting  
    				 if( max_download_rate_bps < 1024 || max_download_rate_bps > UNLIMITED_RATE)
    					 max_download_rate_bps = UNLIMITED_RATE;
    				 else if(USE_REQUEST_LIMITING && FeatureAvailability.isRequestLimitingEnabled())
    					 max_download_rate_bps += Math.max(max_download_rate_bps * 0.1, 5*1024);
    	        
    				 lan_rate_enabled = COConfigurationManager.getBooleanParameter("LAN Speed Enabled");
    				 max_lan_download_rate_bps = COConfigurationManager.getIntParameter( "Max LAN Download Speed KBs" ) * 1024;
    				 if( max_lan_download_rate_bps < 1024 )  max_lan_download_rate_bps = UNLIMITED_RATE;
    				 if( max_lan_download_rate_bps > UNLIMITED_RATE )  max_lan_download_rate_bps = UNLIMITED_RATE;
    	        
     				 refreshRates();
    			 }
    		});
  }

  
  private final WriteController write_controller = new WriteController();
  private final ReadController read_controller = new ReadController();

  
  private final TransferProcessor upload_processor = new TransferProcessor( TransferProcessor.TYPE_UPLOAD, new LimitedRateGroup(){
    @Override
    public int getRateLimitBytesPerSecond() {  return max_upload_rate_bps;  }
  });
  private final TransferProcessor download_processor = new TransferProcessor( TransferProcessor.TYPE_DOWNLOAD, new LimitedRateGroup(){
    @Override
    public int getRateLimitBytesPerSecond() {  return max_download_rate_bps;  }
  });
  
  
  private final TransferProcessor lan_upload_processor = new TransferProcessor( TransferProcessor.TYPE_UPLOAD, new LimitedRateGroup(){
    @Override
    public int getRateLimitBytesPerSecond() {  return max_lan_upload_rate_bps;  }
  });
  private final TransferProcessor lan_download_processor = new TransferProcessor( TransferProcessor.TYPE_DOWNLOAD, new LimitedRateGroup(){
    @Override
    public int getRateLimitBytesPerSecond() {  return max_lan_download_rate_bps;  }
  });
  
  //*************************************
  // added a overlay processor to handle overlay transports
  private final TransferProcessor overlay_upload_processor = new TransferProcessor( TransferProcessor.TYPE_UPLOAD, new LimitedRateGroup(){
    @Override
    public int getRateLimitBytesPerSecond() {  return Math.round(0.96f * max_upload_rate_bps);  }
  });
  private final TransferProcessor overlay_download_processor = new TransferProcessor( TransferProcessor.TYPE_DOWNLOAD, new LimitedRateGroup(){
    @Override
    public int getRateLimitBytesPerSecond() {  return Math.round(0.96f * max_download_rate_bps);  }
  });
  
  private final TransferProcessor lan_overlay_upload_processor = new TransferProcessor( TransferProcessor.TYPE_UPLOAD, new LimitedRateGroup(){
    @Override
    public int getRateLimitBytesPerSecond() {  return Math.round(0.96f * max_lan_upload_rate_bps);  }
  });
  private final TransferProcessor lan_overlay_download_processor = new TransferProcessor( TransferProcessor.TYPE_DOWNLOAD, new LimitedRateGroup(){
    @Override
    public int getRateLimitBytesPerSecond() {  return Math.round(0.96f * max_lan_download_rate_bps);  }
  });
  //*************************************
     
  public static boolean
  isLANRateEnabled()
  {
	  return( lan_rate_enabled );
  }
  
  private final NetworkManagerStats	stats = new NetworkManagerStats();
  
  
  private NetworkManager() {
  }
  
  public static int getMinMssSize() {  return Math.min( TCPNetworkManager.getTcpMssSize(), UDPNetworkManager.getUdpMssSize()); }

  
  private static void refreshRates() {
    if( isSeedingOnlyUploadRate() ) {
      max_upload_rate_bps = max_upload_rate_bps_seeding_only;
    }
    else {
      max_upload_rate_bps = max_upload_rate_bps_normal;
    }
    
    if( max_upload_rate_bps < 1024 ) {
      Debug.out( "max_upload_rate_bps < 1024=" +max_upload_rate_bps);
    }
    
    	//ensure that mss isn't greater than up/down rate limits
    
    int	min_rate = Math.min( max_upload_rate_bps, 
    					Math.min( max_download_rate_bps, 
    						Math.min( max_lan_upload_rate_bps, max_lan_download_rate_bps )));
    
    TCPNetworkManager.refreshRates( min_rate );
    UDPNetworkManager.refreshRates( min_rate );
  }
  
  
  public static boolean isSeedingOnlyUploadRate() {
    return seeding_only_mode_allowed && seeding_only_mode;
  }
  
  public static int getMaxUploadRateBPSNormal() {
    if( max_upload_rate_bps_normal == UNLIMITED_RATE )  return 0;
    return max_upload_rate_bps_normal;
  }
  
  public static int getMaxUploadRateBPSSeedingOnly() {
    if( max_upload_rate_bps_seeding_only == UNLIMITED_RATE )  return 0;
    return max_upload_rate_bps_seeding_only;
  }
  
  /**
   * This method is for display purposes only, the internal rate limiting is 10% higher than returned by this method!
   */
  public static int getMaxDownloadRateBPS() {
    if( max_download_rate_bps == UNLIMITED_RATE )  return 0;
    return external_max_download_rate_bps; 
  }
  
  public static final int CRYPTO_OVERRIDE_NONE			= 0;
  public static final int CRYPTO_OVERRIDE_REQUIRED		= 1;
  public static final int CRYPTO_OVERRIDE_NOT_REQUIRED	= 2;
  
  
  public static boolean
  getCryptoRequired(
	int	override_level )
  {
	  if ( override_level == CRYPTO_OVERRIDE_NONE ){
	    
		  return( REQUIRE_CRYPTO_HANDSHAKE );
		  
	  }else if ( override_level == CRYPTO_OVERRIDE_REQUIRED ){
	    		
		  return( true );
		  
	  }else{
		  
		  return( false );
	  }
  }
  
  public void initialize() {
	HTTPNetworkManager.getSingleton();  
	   
    AzureusCoreFactory.getSingleton().getGlobalManager().addListener( new GlobalManagerListener() {
      @Override
    public void downloadManagerAdded( DownloadManager dm ){}
      @Override
    public void downloadManagerRemoved( DownloadManager dm ){}
      @Override
    public void destroyInitiated(){}
      @Override
    public void destroyed(){}

      @Override
    public void seedingStatusChanged( boolean seeding_only ) {
        seeding_only_mode = seeding_only;
        refreshRates();
      }
    });
  }
  
  
  
  /**
   * Get the singleton instance of the network manager.
   * @return the network manager
   */
  public static NetworkManager getSingleton() {  return instance;  }
  
  
  
  /**
   * Create a new unconnected remote network connection (for outbound-initiated connections).
   * @param remote_address to connect to
   * @param encoder default message stream encoder to use for the outgoing queue
   * @param decoder default message stream decoder to use for the incoming queue
   * @return a new connection
   */
  public NetworkConnection createConnection( ConnectionEndpoint	target, MessageStreamEncoder encoder, MessageStreamDecoder decoder, boolean connect_with_crypto, boolean allow_fallback, byte[][] shared_secrets ) { 
    return NetworkConnectionFactory.create( target, encoder, decoder, connect_with_crypto, allow_fallback, shared_secrets );
  }
  
  
  
  /**
   * Request the acceptance and routing of new incoming connections that match the given initial byte sequence.
   * @param matcher initial byte sequence used for routing
   * @param listener for handling new inbound connections
   * @param factory to use for creating default stream encoder/decoders
   */
  public void 
  requestIncomingConnectionRouting( 
	ByteMatcher 				matcher, 
	final RoutingListener 		listener, 
	final MessageStreamFactory 	factory )
  {
	  IncomingConnectionManager.getSingleton().registerMatchBytes( matcher, new IncomingConnectionManager.MatchListener() {
      @Override
    public boolean
      autoCryptoFallback()
      {
    	return( listener.autoCryptoFallback());
      }
      @Override
    public void connectionMatched( Transport	transport, Object routing_data ) {
        listener.connectionRouted( NetworkConnectionFactory.create( transport, factory.createEncoder(), factory.createDecoder() ), routing_data );
      }
    });
  }
 
  public NetworkConnection
  bindTransport(
	Transport				transport,
	MessageStreamEncoder	encoder,
	MessageStreamDecoder	decoder )
  {
	  return( NetworkConnectionFactory.create( transport, encoder, decoder ));
  }
		
  
  /**
   * Cancel a request for inbound connection routing.
   * @param matcher byte sequence originally used to register
   */
  public void cancelIncomingConnectionRouting( ByteMatcher matcher ) {
	  IncomingConnectionManager.getSingleton().deregisterMatchBytes( matcher );
  }
  

  
  
  /**
   * Add an upload entity for write processing.
   * @param entity to add
   */
  public void addWriteEntity( RateControlledEntity entity ) {
    write_controller.addWriteEntity( entity );
  }
  
  
  /**
   * Remove an upload entity from write processing.
   * @param entity to remove
   */
  public void removeWriteEntity( RateControlledEntity entity ) {
    write_controller.removeWriteEntity( entity );
  }
  
  
  /**
   * Add a download entity for read processing.
   * @param entity to add
   */
  public void addReadEntity( RateControlledEntity entity ) {
    read_controller.addReadEntity( entity );
  }
  
  
  /**
   * Remove a download entity from read processing.
   * @param entity to remove
   */
  public void removeReadEntity( RateControlledEntity entity ) {
    read_controller.removeReadEntity( entity );
  }  
  
    /**
     * Returns a global upload rate handler for either lan or internet traffic.
     * 
     * @param connection
     */
    public RateHandler getUploadRateHandler(int mss, boolean lanLocal) {
        if (lanLocal && lan_rate_enabled) {
            return lan_upload_processor.getMainUploadRateHandler(mss);
        } else {
            return upload_processor.getMainUploadRateHandler(mss);
        }
    }
  
    public void addWriteEventListener(WriteEventListener listener) {
        write_controller.addWriteEventListener(listener);
    }

  /**
   * Register peer connection for network upload and download handling.
   * NOTE: The given max rate limits are ignored until the connection is upgraded.
   * NOTE: The given max rate limits are ignored for LANLocal connections.
   * @param peer_connection to register for network transfer processing
   * @param upload_group upload rate limit group
   * @param download_group download rate limit group
   */
  public void 
  startTransferProcessing( 
	NetworkConnectionBase 	peer_connection )
  {
  	//************************************
  	if( peer_connection.getTransportBase().getDescription().startsWith(NetworkManager.OSF2F_TRANSPORT_PREFIX)){
  		if( peer_connection.isLANLocal() && lan_rate_enabled ) {
    		lan_overlay_upload_processor.registerPeerConnection(peer_connection, true);
    		lan_overlay_download_processor.registerPeerConnection(peer_connection, false);
   		} else {
  			overlay_upload_processor.registerPeerConnection(peer_connection, true);
  			overlay_download_processor.registerPeerConnection(peer_connection, false);
  		}
  	} else
  	// ***********************************	
  	if( peer_connection.isLANLocal() && lan_rate_enabled ) {
  		lan_upload_processor.registerPeerConnection( peer_connection, true );
  		lan_download_processor.registerPeerConnection( peer_connection, false );
  	}
  	else {
  		upload_processor.registerPeerConnection( peer_connection, true );
  		download_processor.registerPeerConnection( peer_connection, false );
  	}
  }
  
  
  /**
   * Cancel network upload and download handling for the given connection.
   * @param peer_connection to cancel
   */
  public void stopTransferProcessing( NetworkConnectionBase peer_connection ) {
  //************************************
  	if( overlay_upload_processor.isRegistered(peer_connection )){
  		overlay_upload_processor.deregisterPeerConnection(peer_connection);
  		overlay_download_processor.deregisterPeerConnection(peer_connection);
  	} else if( lan_overlay_upload_processor.isRegistered(peer_connection )){
  		lan_overlay_upload_processor.deregisterPeerConnection(peer_connection);
  		lan_overlay_download_processor.deregisterPeerConnection(peer_connection);
  	} else
  	// ***********************************	
  	if( lan_upload_processor.isRegistered( peer_connection )) {
  		lan_upload_processor.deregisterPeerConnection( peer_connection );
  		lan_download_processor.deregisterPeerConnection( peer_connection );
  	}
  	else {
  		upload_processor.deregisterPeerConnection( peer_connection );
  		download_processor.deregisterPeerConnection( peer_connection );
  	}
  }
  
  
    /**
     * Upgrade the given connection to high-speed network transfer handling.
     * 
     * @param peer_connection
     *            to upgrade
     * @param overrideHandler
     *            an additional rate handler for the connection.
     * @param overrideHandler
     *            TODO
     */
    public void upgradeTransferProcessing(NetworkConnectionBase peer_connection,
            final RateHandler overrideHandler) {
  	// **********************************
  	if( overlay_upload_processor.isRegistered( peer_connection )) {
            overlay_upload_processor.upgradePeerConnection(peer_connection, overrideHandler);
            overlay_download_processor.upgradePeerConnection(peer_connection, overrideHandler);
  	} else if( lan_overlay_upload_processor.isRegistered( peer_connection )) {
            lan_overlay_upload_processor.upgradePeerConnection(peer_connection, overrideHandler);
            lan_overlay_download_processor.upgradePeerConnection(peer_connection, overrideHandler);
  	} else 
  	// **********************************
  	if( lan_upload_processor.isRegistered( peer_connection )) {
            lan_upload_processor.upgradePeerConnection(peer_connection, overrideHandler);
            lan_download_processor.upgradePeerConnection(peer_connection, overrideHandler);
  	}
  	else if (upload_processor.isRegistered(peer_connection)){
            upload_processor.upgradePeerConnection(peer_connection, overrideHandler);
            download_processor.upgradePeerConnection(peer_connection, overrideHandler);
  	} else {
			// **********************************
			System.err.println("tried to upgrade peer connection, but it is not registered!!!");
			new Exception().printStackTrace();
			// **********************************
  	}
  }

  /**
   * Downgrade the given connection back to a normal-speed network transfer handling.
   * @param peer_connection to downgrade
   */
  public void downgradeTransferProcessing( NetworkConnectionBase peer_connection ) {
  	//**************************************
  	if( overlay_upload_processor.isRegistered( peer_connection )) {
  		overlay_upload_processor.downgradePeerConnection( peer_connection );
  		overlay_upload_processor.downgradePeerConnection( peer_connection );
  	} else if( lan_overlay_upload_processor.isRegistered( peer_connection )) {
  		lan_overlay_upload_processor.downgradePeerConnection( peer_connection );
  		lan_overlay_upload_processor.downgradePeerConnection( peer_connection );
  	} else 
  	//******************************
  	if( lan_upload_processor.isRegistered( peer_connection )) {
  		lan_upload_processor.downgradePeerConnection( peer_connection );
  		lan_download_processor.downgradePeerConnection( peer_connection );
  	}
  	else {
  		upload_processor.downgradePeerConnection( peer_connection );
  		download_processor.downgradePeerConnection( peer_connection );
  	}
  }
  
  public void
  addRateLimiter(
	NetworkConnectionBase 	peer_connection,
	LimitedRateGroup		group,
	boolean					upload )
  {
	  if ( upload ){
	  	//**************************
	  	if ( overlay_upload_processor.isRegistered( peer_connection )){
	  		overlay_upload_processor.addRateLimiter( peer_connection, group );
	  	} else if ( lan_overlay_upload_processor.isRegistered( peer_connection )){
	  		lan_overlay_upload_processor.addRateLimiter( peer_connection, group );
	  	} else
	  	// *********************
		  if ( lan_upload_processor.isRegistered( peer_connection )){
			  
		  		lan_upload_processor.addRateLimiter( peer_connection, group );
	
		  }else{
		  		upload_processor.addRateLimiter( peer_connection, group );
		  } 
	  }else{
	  	//**************************
	    if ( overlay_download_processor.isRegistered( peer_connection )){  
	    	overlay_download_processor.addRateLimiter( peer_connection, group );
	    } else if ( lan_overlay_download_processor.isRegistered( peer_connection )){  
	    	lan_overlay_download_processor.addRateLimiter( peer_connection, group );
	    } else 
	    // *******************************
		  if ( lan_download_processor.isRegistered( peer_connection )){
			  
			  	lan_download_processor.addRateLimiter( peer_connection, group );
	
		  }else{
		  		download_processor.addRateLimiter( peer_connection, group );
		  } 
	  }
  }
    
  public void
  removeRateLimiter(
	NetworkConnectionBase 	peer_connection,
	LimitedRateGroup		group,
	boolean					upload )
  {
	  if ( upload ){
	  	//*****************************
	  	if ( overlay_upload_processor.isRegistered( peer_connection )){		  
	  			overlay_upload_processor.removeRateLimiter( peer_connection, group );
	  	} else if ( lan_overlay_upload_processor.isRegistered( peer_connection )){		  
	  			lan_overlay_upload_processor.removeRateLimiter( peer_connection, group );
	  	} else
	    //*******************************
		  if ( lan_upload_processor.isRegistered( peer_connection )){
			  
		  		lan_upload_processor.removeRateLimiter( peer_connection, group );
	
		  }else{
		  		upload_processor.removeRateLimiter( peer_connection, group );
		  } 
	  }else{
	  	//*****************************
	  	if ( overlay_download_processor.isRegistered( peer_connection )){
	  		overlay_download_processor.removeRateLimiter( peer_connection, group );
	  	} else if ( lan_overlay_download_processor.isRegistered( peer_connection )){
	  		lan_overlay_download_processor.removeRateLimiter( peer_connection, group );
	  	} else
	  	//*******************************
		  if ( lan_download_processor.isRegistered( peer_connection )){
			  
			  	lan_download_processor.removeRateLimiter( peer_connection, group );
	
		  }else{
		  		download_processor.removeRateLimiter( peer_connection, group );
		  } 
	  }
  }
  
  public NetworkManagerStats
  getStats()
  {
	  return( stats );
  }
  
  
  /**
   * Byte stream match filter for routing.
   */
  public interface ByteMatcher {
	  
	/**
	 * Number of bytes of buffer at or beyond which the "match" method will be called to test for a match
	 * @return
	 */
	  
	public int matchThisSizeOrBigger();
	
    /**
     * Get the max number of bytes this matcher requires. If it fails with this (or more) bytes then
     * the connection will be dropped
     * @return size in bytes
     */
	
    public int maxSize();
    
    /**
     * Get the minimum number of bytes required to determine if this matcher applies
     * @return
     */
    
    public int minSize();
    
    /**
     * Check byte stream for match.
     * @param address the originator of the connection
     * @param to_compare
     * @return  return "routing data" in case of a match, null otherwise
     */
    public Object matches( TransportHelper transport, ByteBuffer to_compare, int port );
    
    /**
     * Check for a minimum match
     * @param to_compare
     * @return return "routing data" in case of a match, null otherwise
     */
    public Object minMatches( TransportHelper transport, ByteBuffer to_compare, int port );
    
    public byte[][] getSharedSecrets();
    
    public int getSpecificPort();
  }
  
  
  
  /**
   * Listener for routing events.
   */
  public interface RoutingListener {
	  
	  /**
	   * Currently if message crypto is on and default fallback for incoming not
	   * enabled then we would bounce incoming messages from non-crypto transports
	   * For example, NAT check
	   * This method allows auto-fallback for such transports
	   * @return
	   */
	public boolean
	autoCryptoFallback();
	
    /**
     * The given incoming connection has been accepted.
     * @param connection accepted
     */
    public void connectionRouted( NetworkConnection connection, Object routing_data );
  }
  
}
