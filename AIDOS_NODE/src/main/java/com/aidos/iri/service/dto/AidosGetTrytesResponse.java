package com.aidos.iri.service.dto;

import java.util.List;

public class AidosGetTrytesResponse extends AidosAbstractResponse {
	
    private String [] trytes;
    
	public static AidosGetTrytesResponse create(List<String> elements) {
		AidosGetTrytesResponse res = new AidosGetTrytesResponse();
		res.trytes = elements.toArray(new String[] {});
		return res;
	}

	public String [] getTrytes() {
		return trytes;
	}
}
