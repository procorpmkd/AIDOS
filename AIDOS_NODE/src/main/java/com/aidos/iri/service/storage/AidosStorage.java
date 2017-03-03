package com.aidos.iri.service.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aidos.iri.AidosMilestone;
import com.aidos.iri.model.AidosHash;
import com.aidos.iri.model.AidosTransaction;

/**
 * Storage is organized as 243-value tree
 */
public class AidosStorage extends AidosAbstractStorage {
	
    private static final Logger log = LoggerFactory.getLogger(AidosStorage.class);

    public static final byte[][] approvedTransactionsToStore = new byte[2][];

    private volatile boolean launched;

    public static int numberOfApprovedTransactionsToStore;

    private AidosStorageTransactions storageTransactionInstance = AidosStorageTransactions.instance();
    private AidosStorageBundle storageBundleInstance = AidosStorageBundle.instance();
    private AidosStorageAddresses storageAddressesInstance = AidosStorageAddresses.instance();
    private AidosStorageTags storageTags = AidosStorageTags.instance();
    private AidosStorageApprovers storageApprovers = AidosStorageApprovers.instance();
    private AidosStorageScratchpad storageScratchpad = AidosStorageScratchpad.instance();

    @Override
    public void init() throws IOException {

        synchronized (AidosStorage.class) {
            storageTransactionInstance.init();
            storageBundleInstance.init();
            storageAddressesInstance.init();
            storageTags.init();
            storageApprovers.init();
            storageScratchpad.init();
            storageTransactionInstance.updateBundleAddressTagApprovers();
            launched = true;
        }
    }

    @Override
    public void shutdown() {

        synchronized (AidosStorage.class) {
            if (launched) {
                storageTransactionInstance.shutdown();
                storageBundleInstance.shutdown();
                storageAddressesInstance.shutdown();
                storageTags.shutdown();
                storageApprovers.shutdown();
                storageScratchpad.shutdown();

                log.info("DB successfully flushed");
            }
        }
    }

    void updateBundleAddressTagAndApprovers(final long transactionPointer) {

        final AidosTransaction transaction = new AidosTransaction(mainBuffer, transactionPointer);
        for (int j = 0; j < numberOfApprovedTransactionsToStore; j++) {
            AidosStorageTransactions.instance().storeTransaction(approvedTransactionsToStore[j], null, false);
        }
        numberOfApprovedTransactionsToStore = 0;

        AidosStorageBundle.instance().updateBundle(transactionPointer, transaction);
        AidosStorageAddresses.instance().updateAddresses(transactionPointer, transaction);
        AidosStorageTags.instance().updateTags(transactionPointer, transaction);
        AidosStorageApprovers.instance().updateApprover(transaction.trunkTransaction, transactionPointer);
        
        if (transaction.branchTransactionPointer != transaction.trunkTransactionPointer) {
        	AidosStorageApprovers.instance().updateApprover(transaction.branchTransaction, transactionPointer);
        }
    }
    
    // methods helper
    
    private static AidosStorage instance = new AidosStorage();
    
    private AidosStorage() {}
    
    public static AidosStorage instance() {
		return instance;
	}
}

