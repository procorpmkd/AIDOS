package com.aidos.iri.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aidos.iri.AidosBundle;
import com.aidos.iri.AidosMilestone;
import com.aidos.iri.AidosSnapshot;
import com.aidos.iri.model.AidosHash;
import com.aidos.iri.model.AidosTransaction;
import com.aidos.iri.service.storage.AidosStorage;
import com.aidos.iri.service.storage.AidosStorageApprovers;
import com.aidos.iri.service.storage.AidosStorageScratchpad;
import com.aidos.iri.service.storage.AidosStorageTransactions;

public class AidosTipsManager {

    private static final Logger log = LoggerFactory.getLogger(AidosTipsManager.class);

    private volatile boolean shuttingDown;

    public void init() {

        (new Thread(() -> {
        	
            while (!shuttingDown) {

                try {
                    final int previousLatestMilestoneIndex = AidosMilestone.latestMilestoneIndex;
                    final int previousSolidSubtangleLatestMilestoneIndex = AidosMilestone.latestSolidSubtangleMilestoneIndex;

                    AidosMilestone.updateLatestMilestone();
                    AidosMilestone.updateLatestSolidSubtangleMilestone();

                    if (previousLatestMilestoneIndex != AidosMilestone.latestMilestoneIndex) {
                        log.info("Latest milestone has changed from #" + previousLatestMilestoneIndex + " to #" + AidosMilestone.latestMilestoneIndex);
                    }
                    if (previousSolidSubtangleLatestMilestoneIndex != AidosMilestone.latestSolidSubtangleMilestoneIndex) {
                    	log.info("Latest SOLID SUBTANGLE milestone has changed from #" + previousSolidSubtangleLatestMilestoneIndex + " to #" + AidosMilestone.latestSolidSubtangleMilestoneIndex);
                    }
                    Thread.sleep(5000);

                } catch (final Exception e) {
                	log.error("Error during TipsManager Milestone updating", e);
                }
            }
        }, "Latest Milestone Tracker")).start();
    }

    public void shutDown() {
        shuttingDown = true;
    }

    static synchronized AidosHash transactionToApprove(final AidosHash extraTip, int depth) {

        final AidosHash preferableMilestone = AidosMilestone.latestSolidSubtangleMilestone;

        synchronized (AidosStorageScratchpad.instance().getAnalyzedTransactionsFlags()) {

        	AidosStorageScratchpad.instance().clearAnalyzedTransactionsFlags();

            Map<AidosHash, Long> state = new HashMap<>(AidosSnapshot.initialState);

            {
                int numberOfAnalyzedTransactions = 0;

                final Queue<Long> nonAnalyzedTransactions = new LinkedList<>(Collections.singleton(AidosStorageTransactions.instance().transactionPointer((extraTip == null ? preferableMilestone : extraTip).bytes())));
                Long pointer;
                while ((pointer = nonAnalyzedTransactions.poll()) != null) {

                    if (AidosStorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

                        numberOfAnalyzedTransactions++;

                        final AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction(pointer);
                        if (transaction.type == AidosStorage.PREFILLED_SLOT) {
                            return null;
                        } else {

                            if (transaction.currentIndex == 0) {

                                boolean validBundle = false;

                                final AidosBundle bundle = new AidosBundle(transaction.bundle);
                                for (final List<AidosTransaction> bundleTransactions : bundle.getTransactions()) {

                                    if (bundleTransactions.get(0).pointer == transaction.pointer) {

                                        validBundle = true;

                                        bundleTransactions.stream().filter(bundleTransaction -> bundleTransaction.value != 0).forEach(bundleTransaction -> {
                                            final AidosHash address = new AidosHash(bundleTransaction.address);
                                            final Long value = state.get(address);
                                            state.put(address, value == null ? bundleTransaction.value : (value + bundleTransaction.value));
                                        });
                                        break;
                                    }
                                }

                                if (!validBundle) {
                                    return null;
                                }
                            }

                            nonAnalyzedTransactions.offer(transaction.trunkTransactionPointer);
                            nonAnalyzedTransactions.offer(transaction.branchTransactionPointer);
                        }
                    }
                }

                log.info("Confirmed transactions = {}", numberOfAnalyzedTransactions);
            }

            final Iterator<Map.Entry<AidosHash, Long>> stateIterator = state.entrySet().iterator();
            while (stateIterator.hasNext()) {

                final Map.Entry<AidosHash, Long> entry = stateIterator.next();
                if (entry.getValue() <= 0) {

                    if (entry.getValue() < 0) {
                    	log.error("Ledger inconsistency detected");
                        return null;
                    }
                    stateIterator.remove();
                }
            }

            AidosStorageScratchpad.instance().saveAnalyzedTransactionsFlags();
            AidosStorageScratchpad.instance().clearAnalyzedTransactionsFlags();

            final Set<AidosHash> tailsToAnalyze = new HashSet<>();

            AidosHash tip = preferableMilestone;
            if (extraTip != null) {

                AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction(AidosStorageTransactions.instance().transactionPointer(tip.bytes()));
                while (depth-- > 0 && !tip.equals(AidosHash.NULL_HASH)) {

                    tip = new AidosHash(transaction.hash, 0, AidosTransaction.HASH_SIZE);
                    do {
                        transaction = AidosStorageTransactions.instance().loadTransaction(transaction.trunkTransactionPointer);
                    } while (transaction.currentIndex != 0);
                }
            }
            final Queue<Long> nonAnalyzedTransactions = new LinkedList<>(Collections.singleton(AidosStorageTransactions.instance().transactionPointer(tip.bytes())));
            Long pointer;
            while ((pointer = nonAnalyzedTransactions.poll()) != null) {

                if (AidosStorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

                    final AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction(pointer);

                    if (transaction.currentIndex == 0) {
                        tailsToAnalyze.add(new AidosHash(transaction.hash, 0, AidosTransaction.HASH_SIZE));
                    }

                    AidosStorageApprovers.instance().approveeTransactions(AidosStorageApprovers.instance().approveePointer(transaction.hash)).forEach(nonAnalyzedTransactions::offer);
                }
            }

            if (extraTip != null) {

                AidosStorageScratchpad.instance().loadAnalyzedTransactionsFlags();

                final Iterator<AidosHash> tailsToAnalyzeIterator = tailsToAnalyze.iterator();
                while (tailsToAnalyzeIterator.hasNext()) {

                    final AidosTransaction tail = AidosStorageTransactions.instance().loadTransaction(tailsToAnalyzeIterator.next().bytes());
                    if (AidosStorageScratchpad.instance().analyzedTransactionFlag(tail.pointer)) {
                        tailsToAnalyzeIterator.remove();
                    }
                }
            }

            log.info(tailsToAnalyze.size() + " tails need to be analyzed");
            AidosHash bestTip = preferableMilestone;
            int bestRating = 0;
            for (final AidosHash tail : tailsToAnalyze) {

            	AidosStorageScratchpad.instance().loadAnalyzedTransactionsFlags();

                Set<AidosHash> extraTransactions = new HashSet<>();

                nonAnalyzedTransactions.clear();
                nonAnalyzedTransactions.offer(AidosStorageTransactions.instance().transactionPointer(tail.bytes()));
                while ((pointer = nonAnalyzedTransactions.poll()) != null) {

                    if (AidosStorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

                        final AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction(pointer);
                        if (transaction.type == AidosStorage.PREFILLED_SLOT) {
                            extraTransactions = null;
                            break;
                        } else {
                            extraTransactions.add(new AidosHash(transaction.hash, 0, AidosTransaction.HASH_SIZE));
                            nonAnalyzedTransactions.offer(transaction.trunkTransactionPointer);
                            nonAnalyzedTransactions.offer(transaction.branchTransactionPointer);
                        }
                    }
                }

                if (extraTransactions != null) {

                    Set<AidosHash> extraTransactionsCopy = new HashSet<>(extraTransactions);

                    for (final AidosHash extraTransaction : extraTransactions) {

                        final AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction(extraTransaction.bytes());
                        if (transaction != null && transaction.currentIndex == 0) {

                            final AidosBundle bundle = new AidosBundle(transaction.bundle);
                            for (final List<AidosTransaction> bundleTransactions : bundle.getTransactions()) {

                                if (Arrays.equals(bundleTransactions.get(0).hash, transaction.hash)) {

                                    for (final AidosTransaction bundleTransaction : bundleTransactions) {

                                        if (!extraTransactionsCopy.remove(new AidosHash(bundleTransaction.hash, 0, AidosTransaction.HASH_SIZE))) {
                                            extraTransactionsCopy = null;
                                            break;
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                        if (extraTransactionsCopy == null) {
                            break;
                        }
                    }

                    if (extraTransactionsCopy != null && extraTransactionsCopy.isEmpty()) {

                        final Map<AidosHash, Long> stateCopy = new HashMap<>(state);

                        for (final AidosHash extraTransaction : extraTransactions) {

                            final AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction(extraTransaction.bytes());
                            if (transaction.value != 0) {
                                final AidosHash address = new AidosHash(transaction.address);
                                final Long value = stateCopy.get(address);
                                stateCopy.put(address, value == null ? transaction.value : (value + transaction.value));
                            }
                        }

                        for (final long value : stateCopy.values()) {
                            if (value < 0) {
                                extraTransactions = null;
                                break;
                            }
                        }

                        if (extraTransactions != null) {
                            if (extraTransactions.size() > bestRating) {
                                bestTip = tail;
                                bestRating = extraTransactions.size();
                            }
                        }
                    }
                }
            }
            log.info("{} extra transactions approved", bestRating);
            return bestTip;
        }
    }
    
    private static AidosTipsManager instance = new AidosTipsManager();

    private AidosTipsManager() {}

    public static AidosTipsManager instance() {
        return instance;
    }
}
