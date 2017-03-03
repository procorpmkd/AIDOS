package com.aidos.iri.service.dto;

public class AidosExceptionResponse extends AidosAbstractResponse {
	
	private String exception;

	public static AidosAbstractResponse create(String exception) {
		AidosExceptionResponse res = new AidosExceptionResponse();
		res.exception = exception;
		return res;
	}

	public String getException() {
		return exception;
	}
}
