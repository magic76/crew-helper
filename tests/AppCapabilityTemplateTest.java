package com.crewpocket.helper;

import java.util.HashMap;

public final class AppCapabilityTemplateTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(AppCapabilityTemplate.validCapabilityId("OPEN_PRODUCT"),
                "uppercase id accepted");
        check(!AppCapabilityTemplate.validCapabilityId("open-product"),
                "lowercase dashed id rejected");

        check(AppCapabilityTemplate.requiredParams(
                        "myapp://product/{id}?tab={tab}").size() == 2,
                "placeholder discovery");

        HashMap<String, String> params = new HashMap<String, String>();
        params.put("id", "A B");
        params.put("tab", "詳細");
        String expanded = AppCapabilityTemplate.expand(
                "myapp://product/{id}?tab={tab}",
                params);
        check(expanded.contains("A%20B"),
                "path parameter URL encoded");
        check(expanded.contains("%E8%A9%B3%E7%B4%B0"),
                "unicode parameter encoded");

        HashMap<String, String> url = new HashMap<String, String>();
        url.put("url", "https://example.com/a?q=1");
        check("https://example.com/a?q=1".equals(
                        AppCapabilityTemplate.expand("{url}", url)),
                "whole URL parameter stays raw after validation");

        expectFailure(
                "missing param",
                new ThrowingRunnable() {
                    @Override public void run() throws Exception {
                        AppCapabilityTemplate.expand(
                                "myapp://product/{id}",
                                new HashMap<String, String>());
                    }
                });

        expectFailure(
                "javascript blocked",
                new ThrowingRunnable() {
                    @Override public void run() throws Exception {
                        HashMap<String, String> p =
                                new HashMap<String, String>();
                        p.put("url", "javascript:alert(1)");
                        AppCapabilityTemplate.expand("{url}", p);
                    }
                });

        expectFailure(
                "intent scheme blocked",
                new ThrowingRunnable() {
                    @Override public void run() throws Exception {
                        AppCapabilityTemplate.expand(
                                "intent://scan/#Intent;scheme=zxing;end",
                                new HashMap<String, String>());
                    }
                });

        System.out.println(
                "PASS AppCapabilityTemplateTest: "
                        + checks + " checks");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void expectFailure(
            String message,
            ThrowingRunnable runnable) throws Exception {
        checks++;
        try {
            runnable.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
