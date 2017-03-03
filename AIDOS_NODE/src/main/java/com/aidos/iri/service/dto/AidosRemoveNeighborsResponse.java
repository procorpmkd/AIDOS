package com.aidos.iri.service.dto;

public class AidosRemoveNeighborsResponse extends AidosAbstractResponse {
	
	private int removedNeighbors;

	public static AidosAbstractResponse create(int numberOfRemovedNeighbors) {
		AidosRemoveNeighborsResponse res = new AidosRemoveNeighborsResponse();
		res.removedNeighbors = numberOfRemovedNeighbors;
		return res;
	}
	
	public int getRemovedNeighbors() {
		return removedNeighbors;
	}

}
