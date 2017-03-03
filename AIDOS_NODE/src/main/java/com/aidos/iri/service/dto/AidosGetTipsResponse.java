package com.aidos.iri.service.dto;

import java.util.List;

public class AidosGetTipsResponse extends AidosAbstractResponse {
	
	private String [] hashes;

	public static AidosAbstractResponse create(List<String> elements) {
		AidosGetTipsResponse res = new AidosGetTipsResponse();
		res.hashes = elements.toArray(new String[] {});
		return res;
	}
	
	public String[] getHashes() {
		return hashes;
	}

}
