package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

public final class AppCapabilityDocumentPolicyTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String docs =
                "Maps URLs support https://www.google.com/maps/search/?api=1&query=Google%20Sydney "
                + "and https://www.google.com/maps/dir/?api=1&destination=Google%20Sydney.";

        JSONArray source =
                new JSONArray()
                        .put(new JSONObject()
                                .put("id", "SEARCH_PLACE")
                                .put("label", "Search place")
                                .put("uriTemplate",
                                        "https://www.google.com/maps/search/?api=1&query={query}")
                                .put("exampleUri",
                                        "https://www.google.com/maps/search/?api=1&query=Google%20Sydney")
                                .put("intentAction",
                                        "android.intent.action.VIEW")
                                .put("confidence", 0.98)
                                .put("evidence",
                                        "The documentation gives a Maps search URL with query."))
                        .put(new JSONObject()
                                .put("id", "FAKE")
                                .put("label", "Fake")
                                .put("uriTemplate",
                                        "fakeapp://secret/{id}")
                                .put("exampleUri",
                                        "fakeapp://secret/123")
                                .put("intentAction",
                                        "android.intent.action.VIEW")
                                .put("confidence", 0.99)
                                .put("evidence",
                                        "Not in docs."));

        JSONArray clean =
                AppCapabilityDocumentPolicy.validateCandidates(
                                source,
                                docs);
        check(clean.length() == 1,
                "only document-supported candidate survives");
        check("SEARCH_PLACE".equals(
                        clean.optJSONObject(0)
                                .optString("id")),
                "supported candidate preserved");

        JSONArray lowConfidence =
                new JSONArray()
                        .put(new JSONObject()
                                .put("id", "SEARCH_PLACE")
                                .put("label", "Search place")
                                .put("uriTemplate",
                                        "https://www.google.com/maps/search/?api=1&query={query}")
                                .put("exampleUri",
                                        "https://www.google.com/maps/search/?api=1&query=Google%20Sydney")
                                .put("intentAction",
                                        "android.intent.action.VIEW")
                                .put("confidence", 0.3)
                                .put("evidence", "weak"));
        check(AppCapabilityDocumentPolicy.validateCandidates(
                                lowConfidence,
                                docs)
                        .length() == 0,
                "low confidence rejected");

        JSONArray blockedAction =
                new JSONArray()
                        .put(new JSONObject()
                                .put("id", "SEND")
                                .put("label", "Send")
                                .put("uriTemplate",
                                        "https://www.google.com/maps/search/?api=1&query={query}")
                                .put("exampleUri",
                                        "https://www.google.com/maps/search/?api=1&query=Google%20Sydney")
                                .put("intentAction",
                                        "android.intent.action.SEND")
                                .put("confidence", 0.99)
                                .put("evidence", "send"));
        check(AppCapabilityDocumentPolicy.validateCandidates(
                                blockedAction,
                                docs)
                        .length() == 0,
                "unsupported action rejected");

        System.out.println(
                "PASS AppCapabilityDocumentPolicyTest: "
                        + checks + " checks");
    }

    private static void check(
            boolean value,
            String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
