package com.example.shop.support;

public final class Config {

    private Config() {
    }

    public static String baseUrl() {
        return System.getProperty("app.url", "http://localhost:8080");
    }

    public static boolean headless() {
        return Boolean.parseBoolean(System.getProperty("headless", "false"));
    }
}
