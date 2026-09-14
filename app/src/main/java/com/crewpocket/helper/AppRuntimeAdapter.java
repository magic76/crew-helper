package com.crewpocket.helper;

import android.content.Context;

/**
 * App-local deterministic Runtime capability descriptor.
 *
 * Playbooks teach Gemini WHAT IS SPECIAL about an app. Adapters remain Runtime
 * code and own deterministic HOW. An adapter never grants user authorization.
 */
interface AppRuntimeAdapter {
    String id();
    boolean supports(String packageName);
    String displayName(Context context, String packageName);
    String builtInGuidance();
    String displayGuidance(Context context);
}
