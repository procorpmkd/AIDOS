package com.aidos.iri.service.dto;

import java.util.List;

import com.aidos.iri.model.AidosHash;

public class AidosGetBalancesResponse extends AidosAbstractResponse {
	
	private List<String> balances;
	private String milestone;
	private int milestoneIndex;

	public static AidosAbstractResponse create(List<String> elements, AidosHash milestone, int milestoneIndex) {
		AidosGetBalancesResponse res = new AidosGetBalancesResponse();
		res.balances = elements;
		res.milestone = milestone.toString();
		res.milestoneIndex = milestoneIndex;
		return res;
	}
	
	public String getMilestone() {
		return milestone;
	}
	
	public int getMilestoneIndex() {
		return milestoneIndex;
	}
	
	public List<String> getBalances() {
		return balances;
	}
}
