package com.aidos.iri.service.dto;

import java.util.List;

public class AidosFindTransactionsResponse extends AidosAbstractResponse {
	
	private String [] hashes;

	public static AidosAbstractResponse create(List<String> elements) {
		AidosFindTransactionsResponse res = new AidosFindTransactionsResponse();
		res.hashes = elements.toArray(new String[] {});
		return res;
	}
	
	public String[] getHashes() {
		return hashes;
	}
}
