package com.aidos.iri.service.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aidos.iri.model.AidosHash;
import com.aidos.iri.model.AidosTransaction;

public class AidosStorageTransactions extends AidosAbstractStorage {
	
	private static final Logger log = LoggerFactory.getLogger(AidosStorageTransactions.class);
	
	private static final AidosStorageTransactions instance = new AidosStorageTransactions();
	private static final String TRANSACTIONS_FILE_NAME = "transactions.iri";
	
	private FileChannel transactionsChannel;
    private ByteBuffer transactionsTipsFlags;
    
    private final ByteBuffer[] transactionsChunks = new ByteBuffer[MAX_NUMBER_OF_CHUNKS];
    
    public static long transactionsNextPointer = CELLS_OFFSET - SUPER_GROUPS_OFFSET;
    
    @Override
	public void init() throws IOException {
		
        transactionsChannel = FileChannel.open(Paths.get(TRANSACTIONS_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        transactionsTipsFlags = transactionsChannel.map(FileChannel.MapMode.READ_WRITE, TIPS_FLAGS_OFFSET, TIPS_FLAGS_SIZE);
        transactionsChunks[0] = transactionsChannel.map(FileChannel.MapMode.READ_WRITE, SUPER_GROUPS_OFFSET, SUPER_GROUPS_SIZE);
        final long transactionsChannelSize = transactionsChannel.size();
        while (true) {

            if ((transactionsNextPointer & (CHUNK_SIZE - 1)) == 0) {
                transactionsChunks[(int)(transactionsNextPointer >> 27)] = transactionsChannel.map(FileChannel.MapMode.READ_WRITE, SUPER_GROUPS_OFFSET + transactionsNextPointer, CHUNK_SIZE);
            }
            if (transactionsChannelSize - transactionsNextPointer - SUPER_GROUPS_OFFSET > CHUNK_SIZE) {
                transactionsNextPointer += CHUNK_SIZE;
            } else {
            	
                transactionsChunks[(int) (transactionsNextPointer >> 27)].get(mainBuffer);
                boolean empty = true;
            
                for (final int value : mainBuffer) {
                    if (value != 0) {
                        empty = false;
                        break;
                    }
                }
                if (empty) {
                    break;
                }
                transactionsNextPointer += CELL_SIZE;
            }
        }
	}

	public void updateBundleAddressTagApprovers() {
		if (transactionsNextPointer == CELLS_OFFSET - SUPER_GROUPS_OFFSET) {

            // No need to zero "mainBuffer", it already contains only zeros
            setValue(mainBuffer, AidosTransaction.TYPE_OFFSET, FILLED_SLOT);
            appendToTransactions(true);

            emptyMainBuffer();
            setValue(mainBuffer, 128 << 3, CELLS_OFFSET - SUPER_GROUPS_OFFSET);
            ((ByteBuffer)transactionsChunks[0].position((128 + (128 << 8)) << 11)).put(mainBuffer);

            emptyMainBuffer();
            AidosStorage.instance().updateBundleAddressTagAndApprovers(CELLS_OFFSET - SUPER_GROUPS_OFFSET);
        }
	}
	
    @Override
	public void shutdown() {
        ((MappedByteBuffer) transactionsTipsFlags).force();
        for (int i = 0; i < MAX_NUMBER_OF_CHUNKS && transactionsChunks[i] != null; i++) {
            log.info("Flushing transactions chunk #" + i);
            flush(transactionsChunks[i]);
        }
        try {
			transactionsChannel.close();
		} catch (IOException e) {
			log.error("Shutting down Storage Transaction error: ", e);
		}
	}
	
    public void appendToTransactions(final boolean tip) {

        ((ByteBuffer)transactionsChunks[(int)(transactionsNextPointer >> 27)].position((int)(transactionsNextPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

        if (tip) {
            final long index = (transactionsNextPointer - (CELLS_OFFSET - SUPER_GROUPS_OFFSET)) >> 11;
            transactionsTipsFlags.put((int) (index >> 3), (byte) (transactionsTipsFlags.get((int) (index >> 3)) | (1 << (index & 7))));
        }

        if (((transactionsNextPointer += CELL_SIZE) & (CHUNK_SIZE - 1)) == 0) {

            try {
                transactionsChunks[(int)(transactionsNextPointer >> 27)] = transactionsChannel.map(FileChannel.MapMode.READ_WRITE, SUPER_GROUPS_OFFSET + transactionsNextPointer, CHUNK_SIZE);
            } catch (final IOException e) {
            	log.error("Caught exception on appendToTransactions:", e);
            }
        }
    }
    
    public long transactionPointer(final byte[] hash) { // Returns a negative value if the transaction hasn't been seen yet but was referenced

        synchronized (AidosStorage.class) {
        long pointer = ((hash[0] + 128) + ((hash[1] + 128) << 8)) << 11;
        for (int depth = 2; depth < AidosTransaction.HASH_SIZE; depth++) {

            ((ByteBuffer)transactionsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).get(auxBuffer);

            if (auxBuffer[AidosTransaction.TYPE_OFFSET] == GROUP) {
                if ((pointer = value(auxBuffer, (hash[depth] + 128) << 3)) == 0) {
                    return 0;
                }

            } else {

                for (; depth < AidosTransaction.HASH_SIZE; depth++) {
                    if (auxBuffer[AidosTransaction.HASH_OFFSET + depth] != hash[depth]) {
                        return 0;
                    }
                }

                return auxBuffer[AidosTransaction.TYPE_OFFSET] == PREFILLED_SLOT ? -pointer : pointer;
            }
        }
        }
        throw new IllegalStateException("Corrupted storage");
    }

    public AidosTransaction loadTransaction(final long pointer) {
        synchronized (AidosStorage.class) {
            ((ByteBuffer)transactionsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).get(mainBuffer);
            return new AidosTransaction(mainBuffer, pointer);
    	}
    }

    public AidosTransaction loadTransaction(final byte[] hash) {
        synchronized (AidosStorage.class) {
            final long pointer = transactionPointer(hash);
            return pointer > 0 ? loadTransaction(pointer) : null;
        }
    }
    
    public void setTransactionValidity(final long pointer, final int validity) {
        synchronized (AidosStorage.class) {
            transactionsChunks[(int)(pointer >> 27)].put(((int)(pointer & (CHUNK_SIZE - 1))) + AidosTransaction.VALIDITY_OFFSET, (byte)validity);
        }
    }
	
    public boolean tipFlag(final long pointer) {
    	synchronized (AidosStorage.class) {
            final long index = (pointer - (CELLS_OFFSET - SUPER_GROUPS_OFFSET)) >> 11;
            return (transactionsTipsFlags.get((int)(index >> 3)) & (1 << (index & 7))) != 0;
        }
    }
    
    public List<AidosHash> tips() {
    	synchronized (AidosStorage.class) {
            final List<AidosHash> tips = new LinkedList<>();
    
            long pointer = CELLS_OFFSET - SUPER_GROUPS_OFFSET;
            while (pointer < transactionsNextPointer) {
    
                if (tipFlag(pointer)) {
                    tips.add(new AidosHash(loadTransaction(pointer).hash, 0, AidosTransaction.HASH_SIZE));
                }
                pointer += CELL_SIZE;
            }
            return tips;
    	}
    }
    
    public long storeTransaction(final byte[] hash, final AidosTransaction transaction, final boolean tip) { // Returns the pointer or 0 if the transaction was already in the storage and "transaction" value is not null

    	synchronized (AidosStorage.class) {
        long pointer = ((hash[0] + 128) + ((hash[1] + 128) << 8)) << 11, prevPointer = 0;

    MAIN_LOOP:
        for (int depth = 2; depth < AidosTransaction.HASH_SIZE; depth++) {

            ((ByteBuffer)transactionsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).get(mainBuffer);

            if (mainBuffer[AidosTransaction.TYPE_OFFSET] == GROUP) {

                prevPointer = pointer;
                if ((pointer = value(mainBuffer, (hash[depth] + 128) << 3)) == 0) {

                    setValue(mainBuffer, (hash[depth] + 128) << 3, pointer = transactionsNextPointer);
                    ((ByteBuffer)transactionsChunks[(int)(prevPointer >> 27)].position((int)(prevPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                    AidosTransaction.dump(mainBuffer, hash, transaction);
                    appendToTransactions(transaction != null || tip);
                    if (transaction != null) {
                        AidosStorage.instance().updateBundleAddressTagAndApprovers(pointer);
                    }

                    break MAIN_LOOP;
                }

            } else {

                for (int i = depth; i < AidosTransaction.HASH_SIZE; i++) {

                    if (mainBuffer[AidosTransaction.HASH_OFFSET + i] != hash[i]) {

                        final int differentHashByte = mainBuffer[AidosTransaction.HASH_OFFSET + i];

                        ((ByteBuffer)transactionsChunks[(int)(prevPointer >> 27)].position((int)(prevPointer & (CHUNK_SIZE - 1)))).get(mainBuffer);
                        setValue(mainBuffer, (hash[depth - 1] + 128) << 3, transactionsNextPointer);
                        ((ByteBuffer)transactionsChunks[(int)(prevPointer >> 27)].position((int)(prevPointer & (CHUNK_SIZE - 1)))).put(mainBuffer);

                        for (int j = depth; j < i; j++) {

                            emptyMainBuffer();
                            setValue(mainBuffer, (hash[j] + 128) << 3, transactionsNextPointer + CELL_SIZE);
                            appendToTransactions(false);
                        }

                        emptyMainBuffer();
                        setValue(mainBuffer, (differentHashByte + 128) << 3, pointer);
                        setValue(mainBuffer, (hash[i] + 128) << 3, transactionsNextPointer + CELL_SIZE);
                        appendToTransactions(false);

                        AidosTransaction.dump(mainBuffer, hash, transaction);
                        pointer = transactionsNextPointer;
                        appendToTransactions(transaction != null || tip);
                        if (transaction != null) {
                            AidosStorage.instance().updateBundleAddressTagAndApprovers(pointer);
                        }

                        break MAIN_LOOP;
                    }
                }

                if (transaction != null) {

                    if (mainBuffer[AidosTransaction.TYPE_OFFSET] == PREFILLED_SLOT) {
                        AidosTransaction.dump(mainBuffer, hash, transaction);
                        ((ByteBuffer)transactionsChunks[(int)(pointer >> 27)].position((int)(pointer & (CHUNK_SIZE - 1)))).put(mainBuffer);
                        AidosStorage.instance().updateBundleAddressTagAndApprovers(pointer);
                    } else {
                        pointer = 0;
                    }
                }
                break MAIN_LOOP;
            }
        }

        return pointer;
    	}
    }

    public ByteBuffer transactionsTipsFlags() {
		return transactionsTipsFlags;
	}
    
	public static AidosStorageTransactions instance() {
		return instance;
	}

	public AidosTransaction loadMilestone(final AidosHash latestMilestone) {
		return loadTransaction(transactionPointer(latestMilestone.bytes()));
	}
}

