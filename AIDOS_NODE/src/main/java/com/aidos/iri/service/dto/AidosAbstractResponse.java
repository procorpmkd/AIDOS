package com.aidos.iri.service.dto;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public abstract class AidosAbstractResponse {

	private static class Emptyness extends AidosAbstractResponse {}

    private Integer duration;

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
    }

    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this, false);
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj, false);
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
		this.duration = duration;
	}

    public static AidosAbstractResponse createEmptyResponse() {
    	return new Emptyness();
    }

}
