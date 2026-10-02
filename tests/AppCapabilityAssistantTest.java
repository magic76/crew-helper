package com.crewpocket.helper;

public final class AppCapabilityAssistantTest {
    private static int checks;

    public static void main(String[] args) {
        AppCapabilityAssistant.Suggestion product =
                AppCapabilityAssistant.fromExamples(
                        "myshop://product/12345",
                        "myshop://product/98765");
        check(product.reusable, "two examples become reusable");
        check("OPEN_PRODUCT".equals(product.capabilityId),
                "product capability inferred");
        check("myshop://product/{productId}".equals(product.template),
                "product template inferred");
        check("productId".equals(product.paramName),
                "product param inferred");

        AppCapabilityAssistant.Suggestion query =
                AppCapabilityAssistant.fromExamples(
                        "demo://search?q=cat",
                        "demo://search?q=dog");
        check("SEARCH".equals(query.capabilityId),
                "search capability inferred");
        check("demo://search?q={q}".equals(query.template),
                "query param inferred");

        AppCapabilityAssistant.Suggestion exact =
                AppCapabilityAssistant.fromSingle(
                        "spotify:album:abc123");
        check(!exact.reusable, "single example stays exact");
        check("OPEN_ALBUM".equals(exact.capabilityId),
                "single example receives useful name");

        expectFailure(
                "different schemes rejected",
                new Runnable() {
                    @Override public void run() {
                        AppCapabilityAssistant.fromExamples(
                                "one://product/1",
                                "two://product/2");
                    }
                });

        expectFailure(
                "structural difference rejected",
                new Runnable() {
                    @Override public void run() {
                        AppCapabilityAssistant.fromExamples(
                                "demo://product/1/detail",
                                "demo://product/2/review");
                    }
                });

        System.out.println(
                "PASS AppCapabilityAssistantTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void expectFailure(
            String message,
            Runnable runnable) {
        checks++;
        try {
            runnable.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
