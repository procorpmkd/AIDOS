package com.aidos.iri.service.dto;

public class AidosAddedNeighborsResponse extends AidosAbstractResponse {
	
	private int addedNeighbors;

	public static AidosAbstractResponse create(int numberOfAddedNeighbors) {
		AidosAddedNeighborsResponse res = new AidosAddedNeighborsResponse();
		res.addedNeighbors = numberOfAddedNeighbors;
		return res;
	}

	public int getAddedNeighbors() {
		return addedNeighbors;
	}
	
}
