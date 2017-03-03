package com.aidos.iri;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.aidos.iri.hash.AidosCurl;
import com.aidos.iri.hash.AidosISS;
import com.aidos.iri.model.AidosTransaction;
import com.aidos.iri.service.storage.AidosStorageBundle;
import com.aidos.iri.service.storage.AidosStorageTransactions;
import com.aidos.iri.utils.AidosConverter;

/**
 * A bundle is a group of transactions that follow each other from
 * currentIndex=0 to currentIndex=lastIndex
 * 
 * several bundles can form a single branch if they are chained.
 */
public class AidosBundle {

    private final List<List<AidosTransaction>> transactions = new LinkedList<>();

    public AidosBundle(final byte[] bundle) {

        final long bundlePointer = AidosStorageBundle.instance().bundlePointer(bundle);
        if (bundlePointer == 0) {
        	return;
        }
        final Map<Long, AidosTransaction> bundleTransactions = loadTransactionsFromTangle(bundlePointer);
        
        for (AidosTransaction transaction : bundleTransactions.values()) {

            if (transaction.currentIndex == 0 && transaction.validity() >= 0) {

                final List<AidosTransaction> instanceTransactions = new LinkedList<>();

                final long lastIndex = transaction.lastIndex;
                long bundleValue = 0;
                int i = 0;
            MAIN_LOOP:
                while (true) {

                    instanceTransactions.add(transaction);

                    if (transaction.currentIndex != i || transaction.lastIndex != lastIndex
                            || ((bundleValue += transaction.value) < -AidosTransaction.AIDOS_LIMIT || bundleValue > AidosTransaction.AIDOS_LIMIT)) {
                        AidosStorageTransactions.instance().setTransactionValidity(instanceTransactions.get(0).pointer, -1);
                        break;
                    }

                    if (i++ == lastIndex) { // It's supposed to become -3812798742493 after 3812798742493 and to go "down" to -1 but we hope that noone will create such long bundles

                        if (bundleValue == 0) {

                            if (instanceTransactions.get(0).validity() == 0) {

                                final AidosCurl bundleHash = new AidosCurl();
                                for (final AidosTransaction transaction2 : instanceTransactions) {
                                    bundleHash.absorb(transaction2.trits(), AidosTransaction.ESSENCE_TRINARY_OFFSET, AidosTransaction.ESSENCE_TRINARY_SIZE);
                                }
                                final int[] bundleHashTrits = new int[AidosTransaction.BUNDLE_TRINARY_SIZE];
                                bundleHash.squeeze(bundleHashTrits, 0, bundleHashTrits.length);
                                if (Arrays.equals(AidosConverter.bytes(bundleHashTrits, 0, AidosTransaction.BUNDLE_TRINARY_SIZE), instanceTransactions.get(0).bundle)) {

                                    final int[] normalizedBundle = AidosISS.normalizedBundle(bundleHashTrits);

                                    for (int j = 0; j < instanceTransactions.size(); ) {

                                        transaction = instanceTransactions.get(j);
                                        if (transaction.value < 0) { // let's recreate the address of the transaction.

                                            final AidosCurl address = new AidosCurl();
                                            int offset = 0;
                                            do {

                                                address.absorb(
                                                        AidosISS.digest(Arrays.copyOfRange(normalizedBundle, offset, offset = (offset + AidosISS.NUMBER_OF_FRAGMENT_CHUNKS) % (AidosCurl.HASH_LENGTH / AidosConverter.NUMBER_OF_TRITS_IN_A_TRYTE)),
                                                        Arrays.copyOfRange(instanceTransactions.get(j).trits(), AidosTransaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET, AidosTransaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET + AidosTransaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_SIZE)),
                                                        0, AidosCurl.HASH_LENGTH);

                                            } while (++j < instanceTransactions.size()
                                                    && Arrays.equals(instanceTransactions.get(j).address, transaction.address)
                                                    && instanceTransactions.get(j).value == 0);
                                            
                                            final int[] addressTrits = new int[AidosTransaction.ADDRESS_TRINARY_SIZE];
                                            address.squeeze(addressTrits, 0, addressTrits.length);
                                            if (!Arrays.equals(AidosConverter.bytes(addressTrits, 0, AidosTransaction.ADDRESS_TRINARY_SIZE), transaction.address)) {
                                                AidosStorageTransactions.instance().setTransactionValidity(instanceTransactions.get(0).pointer, -1);
                                                break MAIN_LOOP;
                                            }
                                        } else {
                                            j++;
                                        }
                                    }

                                    AidosStorageTransactions.instance().setTransactionValidity(instanceTransactions.get(0).pointer, 1);
                                    transactions.add(instanceTransactions);
                                } else {
                                	AidosStorageTransactions.instance().setTransactionValidity(instanceTransactions.get(0).pointer, -1);
                                }
                            } else {
                                transactions.add(instanceTransactions);
                            }
                        } else {
                            AidosStorageTransactions.instance().setTransactionValidity(instanceTransactions.get(0).pointer, -1);
                        }
                        break;

                    } else {
                        transaction = bundleTransactions.get(transaction.trunkTransactionPointer);
                        if (transaction == null) {
                            break;
                        }
                    }
                }
            }
        }
    }


    private Map<Long, AidosTransaction> loadTransactionsFromTangle(final long bundlePointer) {
        final Map<Long, AidosTransaction> bundleTransactions = new HashMap<>();
        for (final long transactionPointer : AidosStorageBundle.instance().bundleTransactions(bundlePointer)) {
            bundleTransactions
                .put(transactionPointer, AidosStorageTransactions.instance()
                .loadTransaction(transactionPointer));
        }
        return bundleTransactions;
    }
    
    public List<List<AidosTransaction>> getTransactions() {
        return transactions;
    }
}
