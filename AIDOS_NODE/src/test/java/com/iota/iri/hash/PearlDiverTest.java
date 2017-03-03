package com.iota.iri.hash;

import com.aidos.iri.hash.AidosCurl;
import com.aidos.iri.hash.AidosPearlDiver;
import com.aidos.iri.utils.AidosConverter;

import java.util.Random;
import static org.junit.Assert.*;

import org.junit.Test;

public class PearlDiverTest {

	final static int TRYTE_LENGTH = 2673;

	@Test
	public void testRandomTryteHash() {
		AidosPearlDiver pearlDiver = new AidosPearlDiver();
		AidosCurl curl = new AidosCurl();
		String hash;
		int[] hashTrits = new int[AidosCurl.HASH_LENGTH],
				myTrits = new int[AidosCurl.HASH_LENGTH];
		int i = 0,
		testCount = 20,
		minWeightMagnitude = 13,
		numCores = -1; // use n-1 cores
		
		do {
			String trytes = getRandomTrytes();
			myTrits = AidosConverter.trits(trytes);
			pearlDiver.search(myTrits, minWeightMagnitude, numCores);
			curl.absorb(myTrits, 0, myTrits.length);
			curl.squeeze(hashTrits, 0, AidosCurl.HASH_LENGTH);
			curl.reset();
			hash = AidosConverter.trytes(hashTrits);
			boolean success = isAllNines(hash.substring(AidosCurl.HASH_LENGTH/3-minWeightMagnitude/3));
			assertTrue("The hash should have n nines", success);
			if(!success) {
				System.out.println("Failed on iteration " + i);
				System.out.println("Hash: " + hash);
			}
		} while(i++ < testCount);

	}
	
	private String getRandomTrytes() {
		String trytes = "";
		Random rand = new Random();
		int i, r;
		for(i = 0; i < TRYTE_LENGTH; i++) {
			r = rand.nextInt(27);
			trytes += AidosConverter.TRYTE_ALPHABET.charAt(r);
		}
		return trytes;
	}
	
	private boolean isAllNines(String hash) {
		int i;
		for(i = 0; i < hash.length(); i++) {
			if(hash.charAt(i) != '9') {
				return false;
			}
		}
		return true;
	}
}
