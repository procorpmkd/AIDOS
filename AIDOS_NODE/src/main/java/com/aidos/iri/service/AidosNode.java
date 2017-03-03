package com.aidos.iri.service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aidos.iri.AidosMilestone;
import com.aidos.iri.AidosNeighbor;
import com.aidos.iri.conf.AidosConfiguration;
import com.aidos.iri.conf.AidosConfiguration.DefaultConfSettings;
import com.aidos.iri.hash.AidosCurl;
import com.aidos.iri.model.AidosHash;
import com.aidos.iri.model.AidosTransaction;
import com.aidos.iri.service.storage.AidosStorage;
import com.aidos.iri.service.storage.AidosStorageScratchpad;
import com.aidos.iri.service.storage.AidosStorageTransactions;

/**
 * The class node is responsible for managing Thread's connection.
 */
public class AidosNode {

    private static final Logger log = LoggerFactory.getLogger(AidosNode.class);

    private static final AidosNode instance = new AidosNode();

    private static final int TRANSACTION_PACKET_SIZE = 1650;
    private static final int QUEUE_SIZE = 1000;
    private static final int PAUSE_BETWEEN_TRANSACTIONS = 1;

    private DatagramSocket socket;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private final List<AidosNeighbor> neighbors = new CopyOnWriteArrayList<>();
    private final ConcurrentSkipListSet<AidosTransaction> queuedTransactions = weightQueue();

    private final DatagramPacket receivingPacket = new DatagramPacket(new byte[TRANSACTION_PACKET_SIZE],
            TRANSACTION_PACKET_SIZE);
    private final DatagramPacket sendingPacket = new DatagramPacket(new byte[TRANSACTION_PACKET_SIZE],
            TRANSACTION_PACKET_SIZE);
    private final DatagramPacket tipRequestingPacket = new DatagramPacket(new byte[TRANSACTION_PACKET_SIZE],
            TRANSACTION_PACKET_SIZE);

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public void init() throws Exception {

        socket = new DatagramSocket(AidosConfiguration.integer(DefaultConfSettings.TANGLE_RECEIVER_PORT));

        Arrays.stream(AidosConfiguration.string(DefaultConfSettings.NEIGHBORS)
                .split(" "))
                .distinct()
                .filter(s -> !s.isEmpty()).map(AidosNode::uri).map(Optional::get)
                .peek(u -> {
                    if (!"udp".equals(u.getScheme())) {
                        log.warn("WARNING: '{}' is not a valid udp:// uri schema.", u);
                    }
                })
                .filter(u -> "udp".equals(u.getScheme()))
                .map(u -> new AidosNeighbor(new InetSocketAddress(u.getHost(), u.getPort())))
                .peek(u -> {
                    if (AidosConfiguration.booling(DefaultConfSettings.DEBUG)) {
                        log.debug("-> Adding neighbor : {} ", u.getAddress());
                    }
                })
                .forEach(neighbors::add);

        executor.submit(spawnReceiverThread());
        executor.submit(spawnBroadcasterThread());
        executor.submit(spawnTipRequesterThread());
        executor.submit(spawnNeighborDNSRefresherThread());

        executor.shutdown();
    }

    private Map<String, String> neighborIpCache = new HashMap<>();
    
    private Runnable spawnNeighborDNSRefresherThread() {
        return () -> {

            log.info("Spawning Neighbor DNS Refresher Thread");

            while (!shuttingDown.get()) {
                log.info("Checking Neighbors' Ip...");

                try {
                    neighbors.forEach(n -> {
                        final String hostname = n.getAddress().getHostName();
                        checkIp(hostname).ifPresent(ip -> {
                            log.info("DNS Checker: Validating DNS Address '{}' with '{}'", hostname, ip);
                            final String neighborAddress = neighborIpCache.get(hostname);
                            
                            if (neighborAddress == null) {
                                neighborIpCache.put(neighborAddress, ip);
                            } else {
                                if (neighborAddress.equals(ip)) {
                                    log.info("{} seems fine.", hostname);
                                } else {
                                    log.info("CHANGED IP for {}! Updating...", hostname);
                                    
                                    uri("udp://" + hostname).ifPresent(uri -> {
                                        removeNeighbor(uri);
                                        
                                        uri("udp://" + ip).ifPresent(nuri -> {
                                            addNeighbor(nuri);
                                            neighborIpCache.put(hostname, ip);
                                        });
                                    });
                                }
                            }
                        });
                    });

                    Thread.sleep(1000*60*30);
                } catch (final Exception e) {
                    log.error("Neighbor DNS Refresher Thread Exception:", e);
                }
            }
            log.info("Shutting down Neighbor DNS Resolver Thread");
        };
    }
    
    private Optional<String> checkIp(final String dnsName) {
        
        if (StringUtils.isEmpty(dnsName)) {
            return Optional.empty();
        }
        
        InetAddress inetAddress;
        try {
            inetAddress = java.net.InetAddress.getByName(dnsName);
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
        
        final String hostAddress = inetAddress.getHostAddress();
        
        if (StringUtils.equals(dnsName, hostAddress)) { // not a DNS...
            return Optional.empty();
        }
        
        return Optional.of(hostAddress);
    }
    
    private Runnable spawnReceiverThread() {
        return () -> {

            final AidosCurl curl = new AidosCurl();
            final int[] receivedTransactionTrits = new int[AidosTransaction.TRINARY_SIZE];
            final byte[] requestedTransaction = new byte[AidosTransaction.HASH_SIZE];

            log.info("Spawning Receiver Thread");

            final SecureRandom rnd = new SecureRandom();
            long randomTipBroadcastCounter = 0;

            while (!shuttingDown.get()) {

                try {
                    socket.receive(receivingPacket);

                    if (receivingPacket.getLength() == TRANSACTION_PACKET_SIZE) {

                        for (final AidosNeighbor neighbor : neighbors) {
                            if (neighbor.getAddress().equals(receivingPacket.getSocketAddress())) {
                                try {

                                    neighbor.incAllTransactions();
                                    final AidosTransaction receivedTransaction = new AidosTransaction(receivingPacket.getData(),
                                            receivedTransactionTrits, curl);
                                    if (AidosStorageTransactions.instance().storeTransaction(receivedTransaction.hash,
                                            receivedTransaction, false) != 0) {
                                        neighbor.incNewTransactions();
                                        broadcast(receivedTransaction);
                                    }

                                    final long transactionPointer;
                                    System.arraycopy(receivingPacket.getData(), AidosTransaction.SIZE, requestedTransaction,
                                            0, AidosTransaction.HASH_SIZE);
                                    if (Arrays.equals(requestedTransaction, receivedTransaction.hash)) {

                                        if (AidosConfiguration.booling(DefaultConfSettings.EXPERIMENTAL) &&
                                                ++randomTipBroadcastCounter % 3 == 0) {
                                            log.info("Experimental: Random Tip Broadcaster.");

                                            final String [] tips = AidosStorageTransactions.instance().tips().stream()
                                                    .map(AidosHash::toString)
                                                    .toArray(size -> new String[size]);
                                            final String rndTipHash = tips[rnd.nextInt(tips.length)];

                                            transactionPointer = AidosStorageTransactions.instance()
                                                    .transactionPointer(rndTipHash.getBytes());
                                        } else {
                                            transactionPointer = AidosStorageTransactions.instance()
                                                    .transactionPointer(AidosMilestone.latestMilestone.bytes());
                                        }
                                    } else {
                                        transactionPointer = AidosStorageTransactions.instance().transactionPointer(requestedTransaction);
                                    }
                                    if (transactionPointer > AidosStorage.CELLS_OFFSET - AidosStorage.SUPER_GROUPS_OFFSET) {
                                        synchronized (sendingPacket) {
                                            System.arraycopy(
                                                    AidosStorageTransactions.instance()
                                                            .loadTransaction(transactionPointer).bytes,
                                                    0, sendingPacket.getData(), 0, AidosTransaction.SIZE);
                                            AidosStorageScratchpad.instance().transactionToRequest(sendingPacket.getData(),
                                                    AidosTransaction.SIZE);
                                            neighbor.send(sendingPacket);
                                        }
                                    }
                                } catch (final RuntimeException e) {
                                    log.error("Received an Invalid Transaction. Dropping it...");
                                    neighbor.incInvalidTransactions();
                                }
                                break;
                            }
                        }
                    } else {
                        receivingPacket.setLength(TRANSACTION_PACKET_SIZE);
                    }
                } catch (final Exception e) {
                    log.error("Receiver Thread Exception:", e);
                }
            }
            log.info("Shutting down spawning Receiver Thread");
        };
    }

    private Runnable spawnBroadcasterThread() {
        return () -> {

            log.info("Spawning Broadcaster Thread");

            while (!shuttingDown.get()) {

                try {
                    final AidosTransaction transaction = queuedTransactions.pollFirst();
                    if (transaction != null) {

                        for (final AidosNeighbor neighbor : neighbors) {
                            try {
                                synchronized (sendingPacket) {
                                    System.arraycopy(transaction.bytes, 0, sendingPacket.getData(), 0,
                                            AidosTransaction.SIZE);
                                    AidosStorageScratchpad.instance().transactionToRequest(sendingPacket.getData(),
                                            AidosTransaction.SIZE);
                                    neighbor.send(sendingPacket);
                                }
                            } catch (final Exception e) {
                                // ignore
                            }
                        }
                    }
                    Thread.sleep(PAUSE_BETWEEN_TRANSACTIONS);
                } catch (final Exception e) {
                    log.error("Broadcaster Thread Exception:", e);
                }
            }
            log.info("Shutting down Broadcaster Thread");
        };
    }

    private Runnable spawnTipRequesterThread() {
        return () -> {

            log.info("Spawning Tips Requester Thread");

            while (!shuttingDown.get()) {

                try {
                    final AidosTransaction transaction = AidosStorageTransactions.instance()
                            .loadMilestone(AidosMilestone.latestMilestone);
                    System.arraycopy(transaction.bytes, 0, tipRequestingPacket.getData(), 0, AidosTransaction.SIZE);
                    System.arraycopy(transaction.hash, 0, tipRequestingPacket.getData(), AidosTransaction.SIZE,
                            AidosTransaction.HASH_SIZE);

                    neighbors.forEach(n -> n.send(tipRequestingPacket));

                    Thread.sleep(5000);
                } catch (final Exception e) {
                    log.error("Tips Requester Thread Exception:", e);
                }
            }
            log.info("Shutting down Requester Thread");
        };
    }

    private static ConcurrentSkipListSet<AidosTransaction> weightQueue() {
        return new ConcurrentSkipListSet<>((transaction1, transaction2) -> {
            if (transaction1.weightMagnitude == transaction2.weightMagnitude) {
                for (int i = 0; i < AidosTransaction.HASH_SIZE; i++) {
                    if (transaction1.hash[i] != transaction2.hash[i]) {
                        return transaction2.hash[i] - transaction1.hash[i];
                    }
                }
                return 0;
            }
            return transaction2.weightMagnitude - transaction1.weightMagnitude;
        });
    }

    public void broadcast(final AidosTransaction transaction) {
        queuedTransactions.add(transaction);
        if (queuedTransactions.size() > QUEUE_SIZE) {
            queuedTransactions.pollLast();
        }
    }

    public void shutdown() throws InterruptedException {
        shuttingDown.set(true);
        executor.awaitTermination(6, TimeUnit.SECONDS);
    }

    public void send(final DatagramPacket packet) {
        try {
            socket.send(packet);
        } catch (IOException e) {
            // ignore
        }
    }
    
    // helpers methods

    public boolean removeNeighbor(final URI uri) {
        return neighbors.remove(new AidosNeighbor(new InetSocketAddress(uri.getHost(), uri.getPort())));
    }

    public boolean addNeighbor(final URI uri) {
        final AidosNeighbor neighbor = new AidosNeighbor(new InetSocketAddress(uri.getHost(), uri.getPort()));
        if (!AidosNode.instance().getNeighbors().contains(neighbor)) {
            return AidosNode.instance().getNeighbors().add(neighbor);
        }
        return false;
    }
    
    public static Optional<URI> uri(final String uri) {
        try {
            return Optional.of(new URI(uri));
        } catch (URISyntaxException e) {
            log.error("Uri {} raised URI Syntax Exception", uri);
        }
        return Optional.empty();
    }

    public static AidosNode instance() {
        return instance;
    }

    public int queuedTransactionsSize() {
        return queuedTransactions.size();
    }

    public int howManyNeighbors() {
        return neighbors.size();
    }

    public List<AidosNeighbor> getNeighbors() {
        return neighbors;
    }
    
    private AidosNode() {}
}
