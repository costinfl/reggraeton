package com.example.shop.steps;

import io.cucumber.java.ParameterType;

import java.math.BigDecimal;

public class ParameterTypes {

    @ParameterType("\\d+\\.\\d{2}")
    public BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
