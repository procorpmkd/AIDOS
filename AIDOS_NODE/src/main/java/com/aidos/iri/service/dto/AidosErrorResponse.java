package com.aidos.iri.service.dto;

public class AidosErrorResponse extends AidosAbstractResponse {
	
	private String error;

	public static AidosAbstractResponse create(String error) {
		AidosErrorResponse res = new AidosErrorResponse();
		res.error = error;
		return res;
	}

	public String getError() {
		return error;
	}
}
