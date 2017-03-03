package com.aidos.iri.service.dto;

public class AidosGetInclusionStatesResponse extends AidosAbstractResponse {
	
	private boolean [] states; 

	public static AidosAbstractResponse create(boolean[] inclusionStates) {
		AidosGetInclusionStatesResponse res = new AidosGetInclusionStatesResponse();
		res.states = inclusionStates;
		return res;
	}
	
	public boolean[] getStates() {
		return states;
	}

}
