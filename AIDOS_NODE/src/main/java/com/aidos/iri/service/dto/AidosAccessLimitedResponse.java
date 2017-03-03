package com.aidos.iri.service.dto;

/**
 * Created by Adrian on 07.01.2017.
 */
public class AidosAccessLimitedResponse extends AidosAbstractResponse {

    private String error;

    public static AidosAbstractResponse create(String error) {
        AidosAccessLimitedResponse res = new AidosAccessLimitedResponse();
        res.error = error;
        return res;
    }

    public String getError() {
        return error;
    }
}
