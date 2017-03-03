package com.aidos.iri.service;

import static io.undertow.Handlers.path;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.streams.ChannelInputStream;

import com.aidos.iri.Aidos;
import com.aidos.iri.AidosMilestone;
import com.aidos.iri.AidosNeighbor;
import com.aidos.iri.AidosSnapshot;
import com.aidos.iri.conf.AidosConfiguration;
import com.aidos.iri.conf.AidosConfiguration.DefaultConfSettings;
import com.aidos.iri.hash.AidosCurl;
import com.aidos.iri.hash.AidosPearlDiver;
import com.aidos.iri.model.AidosHash;
import com.aidos.iri.model.AidosTransaction;
import com.aidos.iri.service.dto.*;
import com.aidos.iri.service.storage.AidosStorage;
import com.aidos.iri.service.storage.AidosStorageAddresses;
import com.aidos.iri.service.storage.AidosStorageApprovers;
import com.aidos.iri.service.storage.AidosStorageBundle;
import com.aidos.iri.service.storage.AidosStorageScratchpad;
import com.aidos.iri.service.storage.AidosStorageTags;
import com.aidos.iri.service.storage.AidosStorageTransactions;
import com.aidos.iri.utils.AidosConverter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

@SuppressWarnings("unchecked")
public class AidosAPI {

    private static final Logger log = LoggerFactory.getLogger(AidosAPI.class);

    private Undertow server;

    private final Gson gson = new GsonBuilder().create();
    private final AidosPearlDiver pearlDiver = new AidosPearlDiver();

    private final AtomicInteger counter = new AtomicInteger(0);

    public void init() throws IOException {

        final int apiPort = AidosConfiguration.integer(DefaultConfSettings.API_PORT);
        final String apiHost = AidosConfiguration.string(DefaultConfSettings.API_HOST);

        log.debug("Binding JSON-REST API Undertown server on {}:{}", apiHost, apiPort);

        server = Undertow.builder().addHttpListener(apiPort, apiHost)
                .setHandler(path().addPrefixPath("/", new HttpHandler() {
                    @Override
                    public void handleRequest(final HttpServerExchange exchange) throws Exception {
                        if (exchange.isInIoThread()) {
                        
                        	exchange.dispatch(this);
                            return;
                        }
                        processRequest(exchange);
                    }
                })).build();
        server.start();
    }

    private void processRequest(final HttpServerExchange exchange) throws IOException {
        final ChannelInputStream cis = new ChannelInputStream(exchange.getRequestChannel());
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        final long beginningTime = System.currentTimeMillis();
        final String body = IOUtils.toString(cis, StandardCharsets.UTF_8);
        final AidosAbstractResponse response = process(body, exchange.getSourceAddress());
        sendResponse(exchange, response, beginningTime);
    }

    private AidosAbstractResponse process(final String requestString, InetSocketAddress sourceAddress) throws UnsupportedEncodingException {

        try {

            final Map<String, Object> request = gson.fromJson(requestString, Map.class);
            if (request == null) {
                return AidosExceptionResponse.create("Invalid request payload: '" + requestString + "'");
            }

            final String command = (String) request.get("command");
            if (command == null) {
                return AidosErrorResponse.create("COMMAND parameter has not been specified in the request.");
            }

            if (AidosConfiguration.string(DefaultConfSettings.REMOTEAPILIMIT).contains(command) &&
                    !sourceAddress.getAddress().isLoopbackAddress()) {
                return AidosAccessLimitedResponse.create("COMMAND " + command + " is not available on this node");
            }

            log.info("# {} -> Requesting command '{}'", counter.incrementAndGet(), command);

            switch (command) {

                case "addNeighbors": {
                    final List<String> uris = (List<String>) request.get("uris");
                    log.debug("Invoking 'addNeighbors' with {}", uris);
                    return addNeighborsStatement(uris);
                }
                case "attachToTangle": {
                    final AidosHash trunkTransaction = new AidosHash((String) request.get("trunkTransaction"));
                    final AidosHash branchTransaction = new AidosHash((String) request.get("branchTransaction"));
                    final int minWeightMagnitude = ((Double) request.get("minWeightMagnitude")).intValue();
                    final List<String> trytes = (List<String>) request.get("trytes");

                    return attachToTangleStatement(trunkTransaction, branchTransaction, minWeightMagnitude, trytes);
                }
                case "broadcastTransactions": {
                    final List<String> trytes = (List<String>) request.get("trytes");
                    log.debug("Invoking 'broadcastTransactions' with {}", trytes);
                    return broadcastTransactionStatement(trytes);
                }
                case "findTransactions": {
                    return findTransactionStatement(request);
                }
                case "getBalances": {
                    final List<String> addresses = (List<String>) request.get("addresses");
                    final int threshold = ((Double) request.get("threshold")).intValue();
                    return getBalancesStatement(addresses, threshold);
                }
                case "getInclusionStates": {
                    final List<String> trans = (List<String>) request.get("transactions");
                    final List<String> tps = (List<String>) request.get("tips");

                    if (trans == null || tps == null) {
                        return AidosErrorResponse.create("getInclusionStates Bad Request.");
                    }

                   if (invalidSubtangleStatus()) {
                        return AidosErrorResponse
                                .create("This operations cannot be executed: The subtangle has not been updated yet.");
                    }
                    return getInclusionStateStatement(trans, tps);
                }
                case "getNeighbors": {
                    return getNeighborsStatement();
                }
                case "getNodeInfo": {
                    return AidosGetNodeInfoResponse.create(Aidos.NAME, Aidos.VERSION, Runtime.getRuntime().availableProcessors(),
                            Runtime.getRuntime().freeMemory(), System.getProperty("java.version"), Runtime.getRuntime().maxMemory(),
                            Runtime.getRuntime().totalMemory(), AidosMilestone.latestMilestone, AidosMilestone.latestMilestoneIndex,
                            AidosMilestone.latestSolidSubtangleMilestone, AidosMilestone.latestSolidSubtangleMilestoneIndex,
                            AidosNode.instance().howManyNeighbors(), AidosNode.instance().queuedTransactionsSize(),
                            System.currentTimeMillis(), AidosStorageTransactions.instance().tips().size(),
                            AidosStorageScratchpad.instance().getNumberOfTransactionsToRequest());
                }
                case "getTips": {
                    return getTipsStatement();
                }
                case "getTransactionsToApprove": {
                    final int depth = ((Double) request.get("depth")).intValue();
                    if (invalidSubtangleStatus()) {
                        return AidosErrorResponse
                                .create("This operations cannot be executed: The subtangle has not been updated yet.");
                    }
                    return getTransactionToApproveStatement(depth);
                }
                case "getTrytes": {
                    final List<String> hashes = (List<String>) request.get("hashes");
                    log.debug("Executing getTrytesStatement: {}", hashes);
                    return getTrytesStatement(hashes);
                }

                case "interruptAttachingToTangle": {
                    pearlDiver.cancel();
                    return AidosAbstractResponse.createEmptyResponse();
                }
                case "removeNeighbors": {
                    final List<String> uris = (List<String>) request.get("uris");
                    log.debug("Invoking 'removeNeighbors' with {}", uris);
                    return removeNeighborsStatement(uris);
                }

                case "storeTransactions": {
                    List<String> trytes = (List<String>) request.get("trytes");
                    log.debug("Invoking 'storeTransactions' with {}", trytes);
                    return storeTransactionStatement(trytes);
                }
                default:
                    return AidosErrorResponse.create("Command [" + command + "] is unknown");
            }

        } catch (final Exception e) {
            log.error("API Exception: ", e);
            return AidosExceptionResponse.create(e.getLocalizedMessage());
        }
    }

    public static boolean invalidSubtangleStatus() {
   return (AidosMilestone.latestSolidSubtangleMilestoneIndex == AidosMilestone.MILESTONE_START_INDEX);

    }

    private AidosAbstractResponse removeNeighborsStatement(List<String> uris) throws URISyntaxException {
        final AtomicInteger numberOfRemovedNeighbors = new AtomicInteger(0);
        uris.stream().map(AidosNode::uri).map(Optional::get).filter(u -> "udp".equals(u.getScheme())).forEach(u -> {
            if (AidosNode.instance().removeNeighbor(u)) {
                numberOfRemovedNeighbors.incrementAndGet();
            }
        });
        return AidosRemoveNeighborsResponse.create(numberOfRemovedNeighbors.get());
    }

    private AidosAbstractResponse getTrytesStatement(List<String> hashes) {
        final List<String> elements = new LinkedList<>();
        for (final String hash : hashes) {
            final AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction((new AidosHash(hash)).bytes());
            if (transaction != null) {
                elements.add(AidosConverter.trytes(transaction.trits()));
            }
        }
        return AidosGetTrytesResponse.create(elements);
    }

    private synchronized AidosAbstractResponse getTransactionToApproveStatement(final int depth) {
        final AidosHash trunkTransactionToApprove = AidosTipsManager.transactionToApprove(null, depth);
        if (trunkTransactionToApprove == null) {
            return AidosErrorResponse.create("The subtangle is not solid");
        }
        final AidosHash branchTransactionToApprove = AidosTipsManager.transactionToApprove(trunkTransactionToApprove, depth);
        if (branchTransactionToApprove == null) {
            return AidosErrorResponse.create("The subtangle is not solid");
        }
        return AidosGetTransactionsToApproveResponse.create(trunkTransactionToApprove, branchTransactionToApprove);
    }

    private AidosAbstractResponse getTipsStatement() {
        return AidosGetTipsResponse.create(
                AidosStorageTransactions.instance().tips().stream().map(AidosHash::toString).collect(Collectors.toList()));
    }

    private AidosAbstractResponse storeTransactionStatement(final List<String> trys) {
        for (final String trytes : trys) {
            final AidosTransaction transaction = new AidosTransaction(AidosConverter.trits(trytes));
            AidosStorageTransactions.instance().storeTransaction(transaction.hash, transaction, false);
        }
        return AidosAbstractResponse.createEmptyResponse();
    }

    private AidosAbstractResponse getNeighborsStatement() {
        return AidosGetNeighborsResponse.create(AidosNode.instance().getNeighbors());
    }

    private AidosAbstractResponse getInclusionStateStatement(final List<String> trans, final List<String> tps) {

        final List<AidosHash> transactions = trans.stream().map(s -> new AidosHash(s)).collect(Collectors.toList());
        final List<AidosHash> tips = tps.stream().map(s -> new AidosHash(s)).collect(Collectors.toList());

        int numberOfNonMetTransactions = transactions.size();
        final boolean[] inclusionStates = new boolean[numberOfNonMetTransactions];

        synchronized (AidosStorageScratchpad.instance().getAnalyzedTransactionsFlags()) {

            AidosStorageScratchpad.instance().clearAnalyzedTransactionsFlags();

            final Queue<Long> nonAnalyzedTransactions = new LinkedList<>();
            for (final AidosHash tip : tips) {

                final long pointer = AidosStorageTransactions.instance().transactionPointer(tip.bytes());
                if (pointer <= 0) {
                    return AidosErrorResponse.create("One of the tips absents");
                }
                nonAnalyzedTransactions.offer(pointer);
            }

            {
                Long pointer;
                MAIN_LOOP:
                while ((pointer = nonAnalyzedTransactions.poll()) != null) {

                    if (AidosStorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

                        final AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction(pointer);
                        if (transaction.type == AidosStorage.PREFILLED_SLOT) {
                            return AidosErrorResponse.create("The subtangle is not solid");
                        } else {

                            final AidosHash transactionHash = new AidosHash(transaction.hash, 0, AidosTransaction.HASH_SIZE);
                            for (int i = 0; i < inclusionStates.length; i++) {

                                if (!inclusionStates[i] && transactionHash.equals(transactions.get(i))) {

                                    inclusionStates[i] = true;

                                    if (--numberOfNonMetTransactions <= 0) {
                                        break MAIN_LOOP;
                                    }
                                }
                            }
                            nonAnalyzedTransactions.offer(transaction.trunkTransactionPointer);
                            nonAnalyzedTransactions.offer(transaction.branchTransactionPointer);
                        }
                    }
                }
                return AidosGetInclusionStatesResponse.create(inclusionStates);
            }
        }
    }

    private AidosAbstractResponse findTransactionStatement(final Map<String, Object> request) {
        final Set<Long> bundlesTransactions = new HashSet<>();
        if (request.containsKey("bundles")) {
            for (final String bundle : (List<String>) request.get("bundles")) {
                bundlesTransactions.addAll(AidosStorageBundle.instance()
                        .bundleTransactions(AidosStorageBundle.instance().bundlePointer((new AidosHash(bundle)).bytes())));
            }
        }

        final Set<Long> addressesTransactions = new HashSet<>();
        if (request.containsKey("addresses")) {
            final List<String> addresses = (List<String>) request.get("addresses");
            log.debug("Searching: {}", addresses.stream().reduce((a, b) -> a += ',' + b));

            for (final String address : addresses) {
                if (address.length() != 81) {
                    log.error("Address {} doesn't look a valid address", address);
                }
                addressesTransactions.addAll(AidosStorageAddresses.instance()
                        .addressTransactions(AidosStorageAddresses.instance().addressPointer((new AidosHash(address)).bytes())));
            }
        }

        final Set<Long> tagsTransactions = new HashSet<>();
        if (request.containsKey("tags")) {
            for (String tag : (List<String>) request.get("tags")) {
                while (tag.length() < AidosCurl.HASH_LENGTH / AidosConverter.NUMBER_OF_TRITS_IN_A_TRYTE) {
                    tag += AidosConverter.TRYTE_ALPHABET.charAt(0);
                }
                tagsTransactions.addAll(AidosStorageTags.instance()
                        .tagTransactions(AidosStorageTags.instance().tagPointer((new AidosHash(tag)).bytes())));
            }
        }

        final Set<Long> approveeTransactions = new HashSet<>();

        if (request.containsKey("approvees")) {
            for (final String approvee : (List<String>) request.get("approvees")) {
                approveeTransactions.addAll(AidosStorageApprovers.instance().approveeTransactions(
                        AidosStorageApprovers.instance().approveePointer((new AidosHash(approvee)).bytes())));
            }
        }

        // need refactoring
        final Set<Long> foundTransactions = bundlesTransactions.isEmpty() ? (addressesTransactions.isEmpty()
                ? (tagsTransactions.isEmpty()
                ? (approveeTransactions.isEmpty() ? new HashSet<>() : approveeTransactions) : tagsTransactions)
                : addressesTransactions) : bundlesTransactions;

        if (!addressesTransactions.isEmpty()) {
            foundTransactions.retainAll(addressesTransactions);
        }
        if (!tagsTransactions.isEmpty()) {
            foundTransactions.retainAll(tagsTransactions);
        }
        if (!approveeTransactions.isEmpty()) {
            foundTransactions.retainAll(approveeTransactions);
        }

        final List<String> elements = foundTransactions.stream()
                .map(pointer -> new AidosHash(AidosStorageTransactions.instance().loadTransaction(pointer).hash, 0,
                        AidosTransaction.HASH_SIZE).toString())
                .collect(Collectors.toCollection(LinkedList::new));

        return AidosFindTransactionsResponse.create(elements);
    }

    private AidosAbstractResponse broadcastTransactionStatement(final List<String> trytes2) {
        for (final String tryte : trytes2) {
            final AidosTransaction transaction = new AidosTransaction(AidosConverter.trits(tryte));
            transaction.weightMagnitude = AidosCurl.HASH_LENGTH;
            AidosNode.instance().broadcast(transaction);
        }
        return AidosAbstractResponse.createEmptyResponse();
    }

    private AidosAbstractResponse getBalancesStatement(final List<String> addrss, final int threshold) {

        if (threshold <= 0 || threshold > 100) {
            return AidosErrorResponse.create("Illegal 'threshold'");
        }

        final List<AidosHash> addresses = addrss.stream().map(address -> (new AidosHash(address)))
                .collect(Collectors.toCollection(LinkedList::new));

        final Map<AidosHash, Long> balances = new HashMap<>();
        for (final AidosHash address : addresses) {
            balances.put(address,
                    AidosSnapshot.initialState.containsKey(address) ? AidosSnapshot.initialState.get(address) : Long.valueOf(0));
        }

        final AidosHash milestone = AidosMilestone.latestSolidSubtangleMilestone;
        final int milestoneIndex = AidosMilestone.latestSolidSubtangleMilestoneIndex;

        synchronized (AidosStorageScratchpad.instance().getAnalyzedTransactionsFlags()) {

            AidosStorageScratchpad.instance().clearAnalyzedTransactionsFlags();

            final Queue<Long> nonAnalyzedTransactions = new LinkedList<>(
                    Collections.singleton(AidosStorageTransactions.instance().transactionPointer(milestone.bytes())));
            Long pointer;
            while ((pointer = nonAnalyzedTransactions.poll()) != null) {

                if (AidosStorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

                    final AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction(pointer);

                    if (transaction.value != 0) {

                        final AidosHash address = new AidosHash(transaction.address, 0, AidosTransaction.ADDRESS_SIZE);
                        final Long balance = balances.get(address);
                        if (balance != null) {

                            balances.put(address, balance + transaction.value);
                        }
                    }
                    nonAnalyzedTransactions.offer(transaction.trunkTransactionPointer);
                    nonAnalyzedTransactions.offer(transaction.branchTransactionPointer);
                }
            }
        }

        final List<String> elements = addresses.stream().map(address -> balances.get(address).toString())
                .collect(Collectors.toCollection(LinkedList::new));

        return AidosGetBalancesResponse.create(elements, milestone, milestoneIndex);
    }

    private synchronized AidosAbstractResponse attachToTangleStatement(final AidosHash trunkTransaction, final AidosHash branchTransaction,
                                                                  final int minWeightMagnitude, final List<String> trytes) {
        final List<AidosTransaction> transactions = new LinkedList<>();

        AidosHash prevTransaction = null;

        for (final String tryte : trytes) {

            final int[] transactionTrits = AidosConverter.trits(tryte);
            System.arraycopy((prevTransaction == null ? trunkTransaction : prevTransaction).trits(), 0,
                    transactionTrits, AidosTransaction.TRUNK_TRANSACTION_TRINARY_OFFSET,
                    AidosTransaction.TRUNK_TRANSACTION_TRINARY_SIZE);
            System.arraycopy((prevTransaction == null ? branchTransaction : trunkTransaction).trits(), 0,
                    transactionTrits, AidosTransaction.BRANCH_TRANSACTION_TRINARY_OFFSET,
                    AidosTransaction.BRANCH_TRANSACTION_TRINARY_SIZE);

            if (!pearlDiver.search(transactionTrits, minWeightMagnitude, 0)) {
                transactions.clear();
                break;
            }
            final AidosTransaction transaction = new AidosTransaction(transactionTrits);
            transactions.add(transaction);
            prevTransaction = new AidosHash(transaction.hash, 0, AidosTransaction.HASH_SIZE);
        }

        final List<String> elements = new LinkedList<>();
        for (int i = transactions.size(); i-- > 0; ) {
            elements.add(AidosConverter.trytes(transactions.get(i).trits()));
        }
        return AidosAttachToTangleResponse.create(elements);
    }

    private AidosAbstractResponse addNeighborsStatement(final List<String> uris) throws URISyntaxException {

        int numberOfAddedNeighbors = 0;
        for (final String uriString : uris) {
            final URI uri = new URI(uriString);
            if ("udp".equals(uri.getScheme())) {
                final AidosNeighbor neighbor = new AidosNeighbor(new InetSocketAddress(uri.getHost(), uri.getPort()));
                if (!AidosNode.instance().getNeighbors().contains(neighbor)) {
                    AidosNode.instance().getNeighbors().add(neighbor);
                    numberOfAddedNeighbors++;
                }
            }
        }
        return AidosAddedNeighborsResponse.create(numberOfAddedNeighbors);
    }

    private void sendResponse(final HttpServerExchange exchange, final AidosAbstractResponse res, final long beginningTime)
            throws IOException {
        res.setDuration((int) (System.currentTimeMillis() - beginningTime));
        final String response = gson.toJson(res);

        if (res instanceof AidosErrorResponse) {
            exchange.setStatusCode(400); // bad request
        } else if (res instanceof AidosAccessLimitedResponse) {
            exchange.setStatusCode(401); // api method not allowed
        } else if (res instanceof AidosExceptionResponse) {
            exchange.setStatusCode(500); // internal error
        }

        setupResponseHeaders(exchange);

        ByteBuffer responseBuf = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
        exchange.setResponseContentLength(responseBuf.array().length);
        StreamSinkChannel sinkChannel = exchange.getResponseChannel();
        sinkChannel.getWriteSetter().set( channel -> {
            if (responseBuf.remaining() > 0)
                try {
                    sinkChannel.write(responseBuf);
                    if (responseBuf.remaining() == 0) {
                        exchange.endExchange();
                    }
                } catch (IOException e) {
                    log.error("Error writing response",e);
                    exchange.endExchange();
                }
            else {
                exchange.endExchange();
            }
        });
        sinkChannel.resumeWrites();
    }

    private static void setupResponseHeaders(final HttpServerExchange exchange) {
        final HeaderMap headerMap = exchange.getResponseHeaders();
        headerMap.add(new HttpString("Access-Control-Allow-Origin"),
                AidosConfiguration.string(DefaultConfSettings.CORS_ENABLED));
        headerMap.add(new HttpString("Keep-Alive"), "timeout=500, max=100");
    }

    public void shutDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static AidosAPI instance = new AidosAPI();

    public static AidosAPI instance() {
        return instance;
    }
}
