package com.aidos.iri.service.dto;

import com.aidos.iri.model.AidosHash;

public class AidosGetTransactionsToApproveResponse extends AidosAbstractResponse {

    private String trunkTransaction;
    private String branchTransaction;

	public static AidosAbstractResponse create(AidosHash trunkTransactionToApprove, AidosHash branchTransactionToApprove) {
		AidosGetTransactionsToApproveResponse res = new AidosGetTransactionsToApproveResponse();
		res.trunkTransaction = trunkTransactionToApprove.toString();
		res.branchTransaction = branchTransactionToApprove.toString();
		return res;
	}
	
	public String getBranchTransaction() {
		return branchTransaction;
	}
	
	public String getTrunkTransaction() {
		return trunkTransaction;
	}
}
