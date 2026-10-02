package com.crewpocket.helper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded detection of standard Android intents a specific installed app accepts. */
final class AppCapabilityProbe {
    private AppCapabilityProbe() {}

    static JSONArray detect(Context context, String packageName) {
        JSONArray out = new JSONArray();
        if (context == null || packageName == null || packageName.trim().isEmpty()) {
            return out;
        }
        String pkg = packageName.trim();

        maybeAdd(
                context,
                pkg,
                out,
                "OPEN_URL",
                "Open web URL",
                Intent.ACTION_VIEW,
                "https://example.com/",
                "{url}",
                new String[]{"url"});
        maybeAdd(
                context,
                pkg,
                out,
                "GEO_SEARCH",
                "Open map/place search",
                Intent.ACTION_VIEW,
                "geo:0,0?q=Taipei",
                "geo:0,0?q={query}",
                new String[]{"query"});
        maybeAdd(
                context,
                pkg,
                out,
                "EMAIL",
                "Compose email",
                Intent.ACTION_SENDTO,
                "mailto:test@example.com",
                "mailto:{address}",
                new String[]{"address"});
        maybeAdd(
                context,
                pkg,
                out,
                "DIAL",
                "Open dialer",
                Intent.ACTION_DIAL,
                "tel:12345678",
                "tel:{number}",
                new String[]{"number"});
        return out;
    }

    private static void maybeAdd(
            Context context,
            String pkg,
            JSONArray out,
            String id,
            String label,
            String action,
            String sampleUri,
            String uriTemplate,
            String[] params) {
        try {
            Intent intent = new Intent(action, Uri.parse(sampleUri));
            intent.setPackage(pkg);
            PackageManager pm = context.getPackageManager();
            if (intent.resolveActivity(pm) == null) return;

            JSONObject capability = new JSONObject()
                    .put("id", id)
                    .put("label", label)
                    .put("kind", "INTENT")
                    .put("action", action)
                    .put("uriTemplate", uriTemplate)
                    .put("enabled", true)
                    .put("source", "standard")
                    .put("risk", "LOW");
            JSONArray args = new JSONArray();
            for (String param : params) args.put(param);
            capability.put("params", args);
            out.put(capability);
        } catch (Exception ignored) {}
    }
}
