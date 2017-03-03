package com.aidos.iri.service.dto;

import java.util.List;

public class AidosAttachToTangleResponse extends AidosAbstractResponse {

	private List<String> trytes;
	
	public static AidosAbstractResponse create(List<String> elements) {
		AidosAttachToTangleResponse res = new AidosAttachToTangleResponse();
		res.trytes = elements;
		return res;
	}
	
	public List<String> getTrytes() {
		return trytes;
	}
}
