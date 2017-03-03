package com.aidos.iri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.aidos.iri.hash.AidosCurl;
import com.aidos.iri.hash.AidosISS;
import com.aidos.iri.model.AidosHash;
import com.aidos.iri.model.AidosTransaction;
import com.aidos.iri.service.storage.AidosAbstractStorage;
import com.aidos.iri.service.storage.AidosStorage;
import com.aidos.iri.service.storage.AidosStorageAddresses;
import com.aidos.iri.service.storage.AidosStorageScratchpad;
import com.aidos.iri.service.storage.AidosStorageTransactions;
import com.aidos.iri.utils.AidosConverter;

public class AidosMilestone {

    public static final AidosHash COORDINATOR = new AidosHash("KPWCHICGJZXKE9GSUDXZYUAPLHAKAHYHDXNPHENTERYMMBQOPSQIDENXKLKCEYCPVTZQLEEJVYJZV9BWU");

    public static AidosHash latestMilestone = AidosHash.NULL_HASH;
    public static AidosHash latestSolidSubtangleMilestone = AidosHash.NULL_HASH;

    public static final int MILESTONE_START_INDEX = 13250;

    public static int latestMilestoneIndex = MILESTONE_START_INDEX;
    public static int latestSolidSubtangleMilestoneIndex = MILESTONE_START_INDEX;

    private static final Set<Long> analyzedMilestoneCandidates = new HashSet<>();
    private static final Map<Integer, AidosHash> milestones = new ConcurrentHashMap<>();

    public static void updateLatestMilestone() { // refactor

        for (final Long pointer : AidosStorageAddresses.instance().addressesOf(COORDINATOR)) {

            if (analyzedMilestoneCandidates.add(pointer)) {

                final AidosTransaction transaction = AidosStorageTransactions.instance().loadTransaction(pointer);
                if (transaction.currentIndex == 0) {

                    final int index = (int) AidosConverter.longValue(transaction.trits(), AidosTransaction.TAG_TRINARY_OFFSET, 15);
                    if (index > latestMilestoneIndex) {

                        final AidosBundle bundle = new AidosBundle(transaction.bundle);
                        for (final List<AidosTransaction> bundleTransactions : bundle.getTransactions()) {

                            if (bundleTransactions.get(0).pointer == transaction.pointer) {

                                final AidosTransaction transaction2 = AidosStorageTransactions.instance().loadTransaction(transaction.trunkTransactionPointer);
                                if (transaction2.type == AidosAbstractStorage.FILLED_SLOT
                                        && transaction.branchTransactionPointer == transaction2.trunkTransactionPointer) {

                                    final int[] trunkTransactionTrits = new int[AidosTransaction.TRUNK_TRANSACTION_TRINARY_SIZE];
                                    AidosConverter.getTrits(transaction.trunkTransaction, trunkTransactionTrits);
                                    final int[] signatureFragmentTrits = Arrays.copyOfRange(transaction.trits(), AidosTransaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET, AidosTransaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET + AidosTransaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_SIZE);

                                    final int[] hash = AidosISS.address(AidosISS.digest(Arrays.copyOf(AidosISS.normalizedBundle(trunkTransactionTrits), AidosISS.NUMBER_OF_FRAGMENT_CHUNKS), signatureFragmentTrits));

                                    int indexCopy = index;
                                    for (int i = 0; i < 20; i++) {

                                        final AidosCurl curl = new AidosCurl();
                                        if ((indexCopy & 1) == 0) {
                                            curl.absorb(hash, 0, hash.length);
                                            curl.absorb(transaction2.trits(), i * AidosCurl.HASH_LENGTH, AidosCurl.HASH_LENGTH);
                                        } else {
                                            curl.absorb(transaction2.trits(), i * AidosCurl.HASH_LENGTH, AidosCurl.HASH_LENGTH);
                                            
                                            curl.absorb(hash, 0, hash.length);
                                        }
                                        curl.squeeze(hash, 0, hash.length);

                                        indexCopy >>= 1;
                                    }

                                    if ((new AidosHash(hash)).equals(COORDINATOR)) {

                                        latestMilestone = new AidosHash(transaction.hash, 0, AidosTransaction.HASH_SIZE);
                                        latestMilestoneIndex = index;

                                        milestones.put(latestMilestoneIndex, latestMilestone);
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    public static void updateLatestSolidSubtangleMilestone() {

        for (int milestoneIndex = latestMilestoneIndex; milestoneIndex > latestSolidSubtangleMilestoneIndex; milestoneIndex--) {

            final AidosHash milestone = milestones.get(milestoneIndex);
            if (milestone != null) {

                boolean solid = true;

                synchronized (AidosStorageScratchpad.instance().getAnalyzedTransactionsFlags()) {

                	AidosStorageScratchpad.instance().clearAnalyzedTransactionsFlags();

                    final Queue<Long> nonAnalyzedTransactions = new LinkedList<>();
                    nonAnalyzedTransactions.offer(AidosStorageTransactions.instance().transactionPointer(milestone.bytes()));
                    Long pointer;
                    while ((pointer = nonAnalyzedTransactions.poll()) != null) {

                        if (AidosStorageScratchpad.instance().setAnalyzedTransactionFlag(pointer)) {

                            final AidosTransaction transaction2 = AidosStorageTransactions.instance().loadTransaction(pointer);
                            if (transaction2.type == AidosAbstractStorage.PREFILLED_SLOT) {
                                solid = false;
                                break;

                            } else {
                                nonAnalyzedTransactions.offer(transaction2.trunkTransactionPointer);
                                nonAnalyzedTransactions.offer(transaction2.branchTransactionPointer);
                            }
                        }
                    }
                }

                if (solid) {
                    latestSolidSubtangleMilestone = milestone;
                    latestSolidSubtangleMilestoneIndex = milestoneIndex;
                    return;
                }
            }
        }
    }
}
