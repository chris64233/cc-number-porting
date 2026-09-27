package com.chris64233.numberporting.api.dto;

public record NumberOwnership(
        String number,
        String currentCarrier,
        String currentCarrierName,
        Long activeOrderId,
        boolean ownershipConsistent) {
}
