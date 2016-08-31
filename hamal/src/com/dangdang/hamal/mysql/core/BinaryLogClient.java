/*
 * 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dangdang.hamal.mysql.core;


import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.dangdang.hamal.conf.TopologyConf.ListenerParam;
import com.dangdang.hamal.event.listener.RefreshTableMetaMapListener;
import com.dangdang.hamal.io.ByteArrayInputStream;
import com.dangdang.hamal.mysql.core.command.AuthenticateCommand;
import com.dangdang.hamal.mysql.core.command.DumpBinaryLogCommand;
import com.dangdang.hamal.mysql.core.command.PingCommand;
import com.dangdang.hamal.mysql.core.command.QueryCommand;
import com.dangdang.hamal.mysql.core.event.Event;
import com.dangdang.hamal.mysql.core.event.EventHeader;
import com.dangdang.hamal.mysql.core.event.EventHeaderV4;
import com.dangdang.hamal.mysql.core.event.EventType;
import com.dangdang.hamal.mysql.core.event.RotateEventData;
import com.dangdang.hamal.mysql.core.event.TableMapEventData;
import com.dangdang.hamal.mysql.core.event.TableMapEventDataCache;
import com.dangdang.hamal.mysql.core.event.parser.ChecksumType;
import com.dangdang.hamal.mysql.core.event.parser.EventDataDeserializationException;
import com.dangdang.hamal.mysql.core.event.parser.EventDeserializer;
import com.dangdang.hamal.mysql.core.jmx.BinaryLogClientMXBean;
import com.dangdang.hamal.mysql.core.network.AuthenticationException;
import com.dangdang.hamal.mysql.core.network.SocketFactory;
import com.dangdang.hamal.mysql.core.network.protocol.ErrorPacket;
import com.dangdang.hamal.mysql.core.network.protocol.GreetingPacket;
import com.dangdang.hamal.mysql.core.network.protocol.PacketChannel;
import com.dangdang.hamal.mysql.core.network.protocol.ResultSetRowPacket;

/**
 * MySQL replication stream client.
 *
 * @author 
 */
public class BinaryLogClient implements BinaryLogClientMXBean {

	private final Logger logger = Logger.getLogger(getClass().getName());

	private final String hostname;
	private final int port;
	private final String schema;
	private final String username;
	private final String password;

	private long serverId = 65535;
	private volatile String binlogFilename;
	private volatile long binlogPosition = 4;

	private EventDeserializer eventDeserializer = new EventDeserializer();

	private final List<Listeners.EventListener> eventListeners = new LinkedList<Listeners.EventListener>();
	private final List<Listeners.LifecycleListener> lifecycleListeners = new LinkedList<Listeners.LifecycleListener>();

	private SocketFactory socketFactory;

	private PacketChannel channel;
	private volatile boolean connected;

	private ThreadFactory threadFactory;

	private boolean keepAlive = true;
	private long keepAliveInterval = TimeUnit.MINUTES.toMillis(1);
	private long keepAliveConnectTimeout = TimeUnit.SECONDS.toMillis(3);

	private volatile ExecutorService keepAliveThreadExecutor;
	private long keepAliveThreadShutdownTimeout = TimeUnit.SECONDS.toMillis(6);

	private final Lock shutdownLock = new ReentrantLock();

	/**
	 * Alias for BinaryLogClient("localhost", 3306, &lt;no schema&gt; = null, username, password).
	 * @see BinaryLogClient#BinaryLogClient(String, int, String, String, String)
	 */
	public BinaryLogClient(String username, String password) {
		this("localhost", 3306, null, username, password);
	}

	/**
	 * Alias for BinaryLogClient("localhost", 3306, schema, username, password).
	 * @see BinaryLogClient#BinaryLogClient(String, int, String, String, String)
	 */
	public BinaryLogClient(String schema, String username, String password) {
		this("localhost", 3306, schema, username, password);
	}

	/**
	 * Alias for BinaryLogClient(hostname, port, &lt;no schema&gt; = null, username, password).
	 * @see BinaryLogClient#BinaryLogClient(String, int, String, String, String)
	 */
	public BinaryLogClient(String hostname, int port, String username, String password) {
		this(hostname, port, null, username, password);
	}

	/**
	 * @param hostname mysql server hostname
	 * @param port mysql server port
	 * @param schema database name, nullable. Note that this parameter has nothing to do with event filtering. It's
	 * used only during the authentication.
	 * @param username login name
	 * @param password password
	 */
	public BinaryLogClient(String hostname, int port, String schema, String username, String password) {
		this.hostname = hostname;
		this.port = port;
		this.schema = schema;
		this.username = username;
		this.password = password;
		eventListeners.add(new RefreshTableMetaMapListener());
	}

	/**
	 * @return server id (65535 by default)
	 * @see #setServerId(long)
	 */
	public long getServerId() {
		return serverId;
	}

	/**
	 * @param serverId server id (in the range from 1 to 2^32 鈥� 1). This value MUST be unique across whole replication
	 * group (that is, different from any other server id being used by any master or slave). Keep in mind that each
	 * binary log client (mysql-binlog-connector-java/BinaryLogClient, mysqlbinlog, etc) should be treated as a
	 * simplified slave and thus MUST also use a different server id.
	 * @see #getServerId()
	 */
	public void setServerId(long serverId) {
		this.serverId = serverId;
	}

	/**
	 * @return binary log filename, nullable (and null be default). Note that this value is automatically tracked by
	 * the client and thus is subject to change (in response to {@link EventType#ROTATE}, for example).
	 * @see #setBinlogFilename(String)
	 */
	public String getBinlogFilename() {
		return binlogFilename;
	}

	/**
	 * @param binlogFilename binary log filename.
	 * Special values are:
	 * <ul>
	 *   <li>null, which turns on automatic resolution (resulting in the last known binlog and position). This is what
	 * happens by default when you don't specify binary log filename explicitly.</li>
	 *   <li>"" (empty string), which instructs server to stream events starting from the oldest known binlog.</li>
	 * </ul>
	 * @see #getBinlogFilename()
	 */
	public void setBinlogFilename(String binlogFilename) {
		this.binlogFilename = binlogFilename;
	}

	/**
	 * @return binary log position of the next event, 4 by default (which is a position of first event). Note that this
	 * value changes with each incoming event.
	 * @see #setBinlogPosition(long)
	 */
	public long getBinlogPosition() {
		return binlogPosition;
	}

	/**
	 * @param binlogPosition binary log position. Any value less than 4 gets automatically adjusted to 4 on connect.
	 * @see #getBinlogPosition()
	 */
	public void setBinlogPosition(long binlogPosition) {
		this.binlogPosition = binlogPosition;
	}

	/**
	 * @return true if "keep alive" thread should be automatically started (default), false otherwise.
	 * @see #setKeepAlive(boolean)
	 */
	public boolean isKeepAlive() {
		return keepAlive;
	}

	/**
	 * @param keepAlive true if "keep alive" thread should be automatically started (recommended and true by default),
	 * false otherwise.
	 * @see #isKeepAlive()
	 */
	public void setKeepAlive(boolean keepAlive) {
		this.keepAlive = keepAlive;
	}

	/**
	 * @return "keep alive" interval in milliseconds, 1 minute by default.
	 * @see #setKeepAliveInterval(long)
	 */
	public long getKeepAliveInterval() {
		return keepAliveInterval;
	}

	/**
	 * @param keepAliveInterval "keep alive" interval in milliseconds.
	 * @see #getKeepAliveInterval()
	 */
	public void setKeepAliveInterval(long keepAliveInterval) {
		this.keepAliveInterval = keepAliveInterval;
	}

	/**
	 * @return "keep alive" connect timeout in milliseconds, 3 seconds by default.
	 * @see #setKeepAliveConnectTimeout(long)
	 */
	public long getKeepAliveConnectTimeout() {
		return keepAliveConnectTimeout;
	}

	/**
	 * @param keepAliveConnectTimeout "keep alive" connect timeout in milliseconds.
	 * @see #getKeepAliveConnectTimeout()
	 */
	public void setKeepAliveConnectTimeout(long keepAliveConnectTimeout) {
		this.keepAliveConnectTimeout = keepAliveConnectTimeout;
	}

	/**
	 * @param eventDeserializer custom event deserializer
	 */
	public void setEventDeserializer(EventDeserializer eventDeserializer) {
		if (eventDeserializer == null) {
			throw new IllegalArgumentException("Event deserializer cannot be NULL");
		}
		this.eventDeserializer = eventDeserializer;
	}

	/**
	 * @param socketFactory custom socket factory. If not provided, socket will be created with "new Socket()".
	 */
	public void setSocketFactory(SocketFactory socketFactory) {
		this.socketFactory = socketFactory;
	}

	/**
	 * @param threadFactory custom thread factory. If not provided, threads will be created using simple "new Thread()".
	 */
	public void setThreadFactory(ThreadFactory threadFactory) {
		this.threadFactory = threadFactory;
	}

	/**
	 * Connect to the replication stream. Note that this method blocks until disconnected.
	 * @throws AuthenticationException in case of failed authentication
	 * @throws IOException if anything goes wrong while trying to connect
	 */
	public void connect() throws IOException {
		if (connected) {
			throw new IllegalStateException("BinaryLogClient is already connected");
		}
		try {
			try {
				Socket socket = socketFactory != null ? socketFactory.createSocket() : new Socket();
				socket.connect(new InetSocketAddress(hostname, port));
				channel = new PacketChannel(socket);
				if (channel.getInputStream().peek() == -1) {
					throw new EOFException();
				}
			} catch (IOException e) {
				throw new IOException("Failed to connect to MySQL on " + hostname + ":" + port +
						". Please make sure it's running.", e);
			}
			GreetingPacket greetingPacket = new GreetingPacket(channel.read());
			authenticate(greetingPacket.getScramble(), greetingPacket.getServerCollation());
			if (binlogFilename == null) {
				fetchBinlogFilenameAndPosition();
			}
			if (binlogPosition < 4) {
				if (logger.isLoggable(Level.WARNING)) {
					logger.warning("Binary log position adjusted from " + binlogPosition + " to " + 4);
				}
				binlogPosition = 4;
			}
			ChecksumType checksumType = fetchBinlogChecksum();
			System.out.println("ChecksumType:"+checksumType);

			if (checksumType != ChecksumType.NONE) {             
				confirmSupportOfChecksum(checksumType);
			}
			channel.write(new DumpBinaryLogCommand(serverId, binlogFilename, binlogPosition));
		} catch (IOException e) {
			if (channel != null && channel.isOpen()) {
				channel.close();
			}
			throw e;
		}
		connected = true;
		if (logger.isLoggable(Level.INFO)) {
			logger.info("Connected to " + hostname + ":" + port + " at " + binlogFilename + "/" + binlogPosition);
		}
		synchronized (lifecycleListeners) {
			for (Listeners.LifecycleListener lifecycleListener : lifecycleListeners) {
				lifecycleListener.onConnect(this);
			}
		}
		if (keepAlive && !isKeepAliveThreadRunning()) {
			spawnKeepAliveThread();
		}
		listenForEventPackets();
	}

	private void authenticate(String salt, int collation) throws IOException {
		AuthenticateCommand authenticateCommand = new AuthenticateCommand(schema, username, password, salt);
		authenticateCommand.setCollation(collation);
		channel.write(authenticateCommand);
		byte[] authenticationResult = channel.read();
		if (authenticationResult[0] != (byte) 0x00 /* ok */) {
			if (authenticationResult[0] == (byte) 0xFF /* error */) {
				byte[] bytes = Arrays.copyOfRange(authenticationResult, 1, authenticationResult.length);
				throw new AuthenticationException(new ErrorPacket(bytes).getErrorMessage());
			}
			throw new AuthenticationException("Unexpected authentication result (" + authenticationResult[0] + ")");
		}
	}

	private void spawnKeepAliveThread() {
		keepAliveThreadExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {

			@Override
			public Thread newThread(Runnable runnable) {
				return newNamedThread(runnable, "blc-keepalive-" + hostname + ":" + port);
			}
		});
		keepAliveThreadExecutor.submit(new Runnable() {
			@Override
			public void run() {
				while (true) {
					try {
						Thread.sleep(keepAliveInterval);
					} catch (InterruptedException e) {
						// expected in case of disconnect
					}
					shutdownLock.lock();
					try {
						if (keepAliveThreadExecutor.isShutdown()) {
							return;
						}
						try {
							channel.write(new PingCommand());
						} catch (IOException e) {
							if (logger.isLoggable(Level.INFO)) {
								logger.info("Trying to restore lost connection to " + hostname + ":" + port);
							}
							try {
								if (isConnected()) {
									disconnectChannel();
								}
								connect(keepAliveConnectTimeout);
							} catch (Exception ce) {
								if (logger.isLoggable(Level.WARNING)) {
									logger.warning("Failed to restore connection to " + hostname + ":" + port +
											". Next attempt in " + keepAliveInterval + "ms");
								}
							}
						}
					} finally {
						shutdownLock.unlock();
					}
				}
			}
		});
	}

	private Thread newNamedThread(Runnable runnable, String threadName) {
		Thread thread = threadFactory == null ? new Thread(runnable) : threadFactory.newThread(runnable);
		thread.setName(threadName);
		return thread;
	}

	boolean isKeepAliveThreadRunning() {
		return keepAliveThreadExecutor != null && !keepAliveThreadExecutor.isShutdown();
	}

	/**
	 * Connect to the replication stream in a separate thread.
	 * @param timeoutInMilliseconds timeout in milliseconds
	 * @throws AuthenticationException in case of failed authentication
	 * @throws IOException if anything goes wrong while trying to connect
	 * @throws TimeoutException if client wasn't able to connect in the requested period of time
	 */
	public void connect(long timeoutInMilliseconds) throws IOException, TimeoutException {
		final CountDownLatch countDownLatch = new CountDownLatch(1);
		Listeners.AbstractLifecycleListener connectListener = new Listeners.AbstractLifecycleListener() {
			@Override
			public void onConnect(BinaryLogClient client) {
				countDownLatch.countDown();
			}
		};
		registerLifecycleListener(connectListener);
		final AtomicReference<IOException> exceptionReference = new AtomicReference<IOException>();
		Runnable runnable = new Runnable() {

			@Override
			public void run() {
				try {
					connect();
				} catch (IOException e) {
					exceptionReference.set(e);
					countDownLatch.countDown(); // making sure we don't end up waiting whole "timeout"
				}
			}
		};
		newNamedThread(runnable, "blc-" + hostname + ":" + port).start();
		boolean started = false;
		try {
			started = countDownLatch.await(timeoutInMilliseconds, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			if (logger.isLoggable(Level.WARNING)) {
				logger.log(Level.WARNING, e.getMessage());
			}
		}
		unregisterLifecycleListener(connectListener);
		if (exceptionReference.get() != null) {
			throw exceptionReference.get();
		}
		if (!started) {
			throw new TimeoutException("BinaryLogClient was unable to connect in " + timeoutInMilliseconds + "ms");
		}
	}

	/**
	 * @return true if client is connected, false otherwise
	 */
	public boolean isConnected() {
		return connected;
	}

	private void fetchBinlogFilenameAndPosition() throws IOException {
		ResultSetRowPacket[] resultSet;
		channel.write(new QueryCommand("show master status"));
		resultSet = readResultSet();
		if (resultSet.length == 0) {
			throw new IOException("Failed to determine binlog filename/position");
		}
		ResultSetRowPacket resultSetRow = resultSet[0];
		binlogFilename = resultSetRow.getValue(0);
		binlogPosition = Long.parseLong(resultSetRow.getValue(1));
	}

	private ChecksumType fetchBinlogChecksum() throws IOException {
		channel.write(new QueryCommand("show global variables like 'binlog_checksum'"));
		ResultSetRowPacket[] resultSet = readResultSet();
		if (resultSet.length == 0) {
			return ChecksumType.NONE;
		}
		return ChecksumType.valueOf(resultSet[0].getValue(1).toUpperCase());
	}

	private void confirmSupportOfChecksum(ChecksumType checksumType) throws IOException {
		channel.write(new QueryCommand("set @master_binlog_checksum= @@global.binlog_checksum"));
		byte[] statementResult = channel.read();
		if (statementResult[0] == (byte) 0xFF /* error */) {
			byte[] bytes = Arrays.copyOfRange(statementResult, 1, statementResult.length);
			throw new IOException(new ErrorPacket(bytes).getErrorMessage());
		}
		eventDeserializer.setChecksumType(checksumType);
	}

	private void listenForEventPackets() throws IOException {
		ByteArrayInputStream inputStream = channel.getInputStream();
		try {
			while (inputStream.peek() != -1) {
				int packetLength = inputStream.readInteger(3);
				inputStream.skip(1); // 1 byte for sequence
				int marker = inputStream.read();
				if (marker == 0xFF) {
					ErrorPacket errorPacket = new ErrorPacket(inputStream.read(packetLength - 1));
					System.out.println(errorPacket.getErrorCode() + " - " + errorPacket.getErrorMessage());
					throw new IOException(errorPacket.getErrorCode() + " - " + errorPacket.getErrorMessage());
				}
				Event event;
				try {
					event = eventDeserializer.nextEvent(inputStream);
					
				} catch (Exception e) {
					Throwable cause = e instanceof EventDataDeserializationException ? e.getCause() : e;
					if (cause instanceof EOFException || cause instanceof SocketException) {
						throw e;
					}
					if (isConnected()) {
						synchronized (lifecycleListeners) {
							for (Listeners.LifecycleListener lifecycleListener : lifecycleListeners) {
								lifecycleListener.onEventDeserializationFailure(this, e);
							}
						}
					}
					continue;
				}
				if (isConnected()) {
					notifyEventListeners(event);
					updateClientBinlogFilenameAndPosition(event);					
				}
			}
		} catch (Exception e) {
			if (isConnected()) {
				synchronized (lifecycleListeners) {
					for (Listeners.LifecycleListener lifecycleListener : lifecycleListeners) {
						lifecycleListener.onCommunicationFailure(this, e);
					}
				}
			}
		} finally {
			if (isConnected()) {
				disconnectChannel();
			}
		}
	}


	
	private void updateClientBinlogFilenameAndPosition(Event event) {
		EventHeader eventHeader = event.getHeader();
		if (eventHeader.getEventType() == EventType.ROTATE) {
			RotateEventData eventData = event.getData();
			if (eventData != null) {
				binlogFilename = eventData.getBinlogFilename();
				binlogPosition = eventData.getBinlogPosition();
			}
		} else
			if (eventHeader instanceof EventHeaderV4) {
				EventHeaderV4 trackableEventHeader = (EventHeaderV4) eventHeader;
				long nextBinlogPosition = trackableEventHeader.getNextPosition();
				if (nextBinlogPosition > 0) {
					binlogPosition = nextBinlogPosition;
				}
			}
	}

	private ResultSetRowPacket[] readResultSet() throws IOException {
		List<ResultSetRowPacket> resultSet = new LinkedList<ResultSetRowPacket>();
		while ((channel.read())[0] != (byte) 0xFE /* eof */) { /* skip */ }
		for (byte[] bytes; (bytes = channel.read())[0] != (byte) 0xFE /* eof */; ) {
			resultSet.add(new ResultSetRowPacket(bytes));
		}
		return resultSet.toArray(new ResultSetRowPacket[resultSet.size()]);
	}

	/**
	 * @return registered event listeners
	 */
	public List<Listeners.EventListener> getEventListeners() {
		return Collections.unmodifiableList(eventListeners);
	}

	/**
	 * Register event listener. Note that multiple event listeners will be called in order they
	 * where registered.
	 */
	public void registerEventListener(Listeners.EventListener eventListener) {
		synchronized (eventListeners) {
			eventListeners.add(eventListener);
		}
	}

	/**
	 * Unregister all event listener of specific type.
	 */
	public void unregisterEventListener(Class<? extends Listeners.EventListener> listenerClass) {
		synchronized (eventListeners) {
			Iterator<Listeners.EventListener> iterator = eventListeners.iterator();
			while (iterator.hasNext()) {
				Listeners.EventListener eventListener = iterator.next();
				if (listenerClass.isInstance(eventListener)) {
					iterator.remove();
				}
			}
		}
	}

	/**
	 * Unregister single event listener.
	 */
	public void unregisterEventListener(Listeners.EventListener eventListener) {
		synchronized (eventListeners) {
			eventListeners.remove(eventListener);
		}
	}

	private void notifyEventListeners(Event event) {
		synchronized (eventListeners) {
			for (Listeners.EventListener eventListener : eventListeners) {
				try {
					eventListener.onEvent(event);
				} catch (Exception e) {
					if (logger.isLoggable(Level.WARNING)) {
						logger.log(Level.WARNING, eventListener + " choked on " + event, e);
					}
				}
			}
		}
	}

	/**
	 * @return registered lifecycle listeners
	 */
	public List<Listeners.LifecycleListener> getLifecycleListeners() {
		return Collections.unmodifiableList(lifecycleListeners);
	}

	/**
	 * Register lifecycle listener. Note that multiple lifecycle listeners will be called in order they
	 * where registered.
	 */
	public void registerLifecycleListener(Listeners.LifecycleListener lifecycleListener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.add(lifecycleListener);
		}
	}

	/**
	 * Unregister all lifecycle listener of specific type.
	 */
	public synchronized void unregisterLifecycleListener(Class<? extends Listeners.LifecycleListener> listenerClass) {
		synchronized (lifecycleListeners) {
			Iterator<Listeners.LifecycleListener> iterator = lifecycleListeners.iterator();
			while (iterator.hasNext()) {
				Listeners.LifecycleListener lifecycleListener = iterator.next();
				if (listenerClass.isInstance(lifecycleListener)) {
					iterator.remove();
				}
			}
		}
	}

	/**
	 * Unregister single lifecycle listener.
	 */
	public synchronized void unregisterLifecycleListener(Listeners.LifecycleListener eventListener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.remove(eventListener);
		}
	}

	/**
	 * Disconnect from the replication stream.
	 * Note that this does not cause binlogFilename/binlogPosition to be cleared out.
	 * As the result following {@link #connect()} resumes client from where it left off.
	 */
	public void disconnect() throws IOException {
		shutdownLock.lock();
		try {
			if (isKeepAliveThreadRunning()) {
				keepAliveThreadExecutor.shutdownNow();
			}
			disconnectChannel();
		} finally {
			shutdownLock.unlock();
		}
		if (isKeepAliveThreadRunning()) {
			waitForKeepAliveThreadToBeTerminated();
		}
	}

	private void waitForKeepAliveThreadToBeTerminated() {
		boolean terminated = false;
		try {
			terminated = keepAliveThreadExecutor.awaitTermination(keepAliveThreadShutdownTimeout,
					TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			if (logger.isLoggable(Level.WARNING)) {
				logger.log(Level.WARNING, e.getMessage());
			}
		}
		if (!terminated) {
			throw new IllegalStateException("BinaryLogClient was unable to shut keep alive thread down in " +
					keepAliveThreadShutdownTimeout + "ms");
		}
	}

	private void disconnectChannel() throws IOException {
		try {
			connected = false;
			if (channel != null && channel.isOpen()) {
				channel.close();
			}
		} finally {
			synchronized (lifecycleListeners) {
				for (Listeners.LifecycleListener lifecycleListener : lifecycleListeners) {
					lifecycleListener.onDisconnect(this);
				}
			}
		}
	}

	/**
	 * {@link BinaryLogClient}'s event listener.
	 */


	

}
