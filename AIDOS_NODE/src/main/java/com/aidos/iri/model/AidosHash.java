package com.aidos.iri.model;

import com.aidos.iri.hash.AidosCurl;
import com.aidos.iri.utils.AidosConverter;

import java.util.Arrays;

public class AidosHash {

    public static final int SIZE_IN_BYTES = 49;

    public static final AidosHash NULL_HASH = new AidosHash(new int[AidosCurl.HASH_LENGTH]);

    private final byte[] bytes;
    private final int hashCode;
    
    // constructors' bill

    public AidosHash(final byte[] bytes, final int offset, final int size) {
        this.bytes = new byte[SIZE_IN_BYTES];
        System.arraycopy(bytes, offset, this.bytes, 0, size);
  
        
        hashCode = Arrays.hashCode(this.bytes);
    }

    public AidosHash(final byte[] bytes) {
        this(bytes, 0, SIZE_IN_BYTES);
    }

    public AidosHash(final int[] trits, final int offset) {
        this(AidosConverter.bytes(trits, offset, AidosCurl.HASH_LENGTH));
    }

    public AidosHash(final int[] trits) {
        this(trits, 0);
    }

    public AidosHash(final String trytes) {
        this(AidosConverter.trits(trytes));
    }

    //
    
    public int[] trits() {
        final int[] trits = new int[AidosCurl.HASH_LENGTH];
        AidosConverter.getTrits(bytes, trits);
        return trits;
    }

    @Override
    public boolean equals(final Object obj) {
        return Arrays.equals(bytes, ((AidosHash)obj).bytes);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return AidosConverter.trytes(trits());
    }
    
    public byte[] bytes() {
		return bytes;
	}
}

