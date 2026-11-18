package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

/** Local staging and review registry; Pocket payloads are never executable code. */
final class HelperCoachRegistryStore {
    private static final String PREFS = "crew_helper_coach_registry_v1";
    private static final String KEY_SKILLS = "skills";
    private static final String KEY_PROPOSALS = "code_proposals";
    private static final int MAX_ITEMS = 80;
    private static final int MAX_VERSIONS = 6;
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    HelperCoachRegistryStore(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        prefs = app == null
                ? null
                : app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    JSONObject stageSkill(JSONObject request) {
        synchronized (LOCK) {
            JSONObject out = new JSONObject();
            try {
                if (prefs == null || request == null) {
                    return out.put("success", false).put("error", "STORE_UNAVAILABLE");
                }
                String id = safeId(request.optString("skill_id", ""));
                String pkg = request.optString("app_package", "").trim();
                JSONArray actions = request.optJSONArray("action_sequence");
                JSONArray pre = cleanStringArray(request.optJSONArray("preconditions"), 12, 120);
                JSONArray post = cleanStringArray(request.optJSONArray("postconditions"), 12, 120);
                JSONArray evidence = cleanStringArray(request.optJSONArray("evidence_refs"), 12, 120);
                if (id.isEmpty() || !validPackage(pkg) || actions == null
                        || actions.length() == 0 || pre == null || post == null
                        || evidence == null || actions.length() > 5) {
                    return out.put("success", false).put("error", "INVALID_SKILL_CANDIDATE");
                }
                JSONArray safeActions = new JSONArray();
                for (int i = 0; i < actions.length(); i++) {
                    String action = actions.optString(i, "").trim();
                    if (!HelperCoachRuntimePolicy.ACTION_INSPECT_UI.equals(action)
                            && !HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP.equals(action)) {
                        return out.put("success", false).put("error", "ACTION_NOT_ALLOWLISTED");
                    }
                    safeActions.put(action);
                }
                JSONArray all = loadSkills();
                JSONObject previous = findSkill(all, id);
                int currentVersion = previous == null ? 0 : previous.optInt("version", 0);
                int version = HelperCoachSkillLifecyclePolicy.nextVersion(
                        currentVersion, request.optInt("version", 0));
                JSONArray history = previous == null
                        ? new JSONArray() : cloneArray(previous.optJSONArray("history"));
                if (previous != null) {
                    JSONObject snapshot = new JSONObject(previous.toString());
                    snapshot.remove("history");
                    history.put(snapshot);
                    while (history.length() > MAX_VERSIONS) history.remove(0);
                    all.remove(indexOfSkill(all, id));
                }
                JSONObject item = new JSONObject()
                        .put("skill_id", id)
                        .put("name", CoachDataRedactor.safeFreeText(
                                request.optString("name", id), 80))
                        .put("app_package", pkg)
                        .put("preconditions", pre)
                        .put("action_sequence", safeActions)
                        .put("postconditions", post)
                        .put("evidence_refs", evidence)
                        .put("status", "CANDIDATE")
                        .put("active", false)
                        .put("version", version)
                        .put("verified_task_ids", new JSONArray())
                        .put("regression_checks_passed", false)
                        .put("history", history)
                        .put("updated_at", System.currentTimeMillis());
                all.put(item);
                saveSkills(trim(all));
                return out.put("success", true)
                        .put("status", "CANDIDATE")
                        .put("skill_id", id)
                        .put("version", version);
            } catch (Exception error) {
                try { return out.put("success", false).put("error", "SKILL_STORE_ERROR"); }
                catch (Exception ignored) { return new JSONObject(); }
            }
        }
    }

    JSONObject recordVerifiedOutcome(String skillId, String taskId, String evidence) {
        synchronized (LOCK) {
            JSONObject out = new JSONObject();
            try {
                JSONArray all = loadSkills();
                JSONObject item = findSkill(all, safeId(skillId));
                String task = taskId == null ? "" : taskId.trim();
                if (item == null || task.isEmpty()
                        || !HelperCoachSkillLifecyclePolicy
                                .isTrustedCompletionEvidence(evidence)) {
                    return out.put("success", false).put("error", "UNTRUSTED_SKILL_EVIDENCE");
                }
                JSONArray tasks = cloneArray(item.optJSONArray("verified_task_ids"));
                for (int i = 0; i < tasks.length(); i++) {
                    if (task.equals(tasks.optString(i, ""))) {
                        return out.put("success", false).put("error", "DUPLICATE_TASK_EVIDENCE");
                    }
                }
                tasks.put(task);
                item.put("verified_task_ids", tasks)
                        .put("last_verification_evidence", CoachDataRedactor.safeErrorCode(evidence))
                        .put("status", tasks.length() >= 2 ? "VERIFIED" : "CANDIDATE")
                        .put("updated_at", System.currentTimeMillis());
                saveSkills(all);
                return out.put("success", true)
                        .put("verified_tasks", tasks.length())
                        .put("status", item.optString("status", "CANDIDATE"));
            } catch (Exception error) {
                try { return out.put("success", false).put("error", "SKILL_STORE_ERROR"); }
                catch (Exception ignored) { return new JSONObject(); }
            }
        }
    }

    /**
     * Attach an independently verified Runtime completion to inactive skill
     * candidates whose declared app and postcondition match the evidence.
     * Pocket advice itself is never used as verification evidence.
     */
    int recordVerifiedOutcomeForCandidates(
            String appPackage, String taskId, String evidence) {
        String pkg = appPackage == null ? "" : appPackage.trim();
        String task = taskId == null ? "" : taskId.trim();
        String proof = evidence == null ? "" : evidence.trim();
        if (!validPackage(pkg) || task.isEmpty()
                || !HelperCoachSkillLifecyclePolicy
                        .isTrustedCompletionEvidence(proof)) return 0;
        synchronized (LOCK) {
            JSONArray all = loadSkills();
            int recorded = 0;
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (item == null || !pkg.equals(item.optString("app_package", ""))) continue;
                JSONArray postconditions = item.optJSONArray("postconditions");
                ArrayList<String> expected = new ArrayList<String>();
                if (postconditions != null) {
                    for (int j = 0; j < postconditions.length(); j++) {
                        expected.add(postconditions.optString(j, ""));
                    }
                }
                if (!HelperCoachSkillLifecyclePolicy.matchesTrustedPostcondition(
                        item.optString("app_package", ""), pkg, expected, proof)) continue;
                JSONObject result = recordVerifiedOutcome(
                        item.optString("skill_id", ""), task, proof);
                if (result.optBoolean("success", false)) recorded++;
            }
            return recorded;
        }
    }

    JSONObject markRegressionChecksPassed(String skillId) {
        synchronized (LOCK) {
            return updateSkill(skillId, new SkillMutation() {
                @Override public JSONObject apply(JSONObject item) throws Exception {
                    JSONArray tasks = item.optJSONArray("verified_task_ids");
                    if (tasks == null || tasks.length() < 2) {
                        return new JSONObject().put("success", false)
                                .put("error", "NEEDS_INDEPENDENT_VERIFICATION");
                    }
                    item.put("regression_checks_passed", true)
                            .put("updated_at", System.currentTimeMillis());
                    return new JSONObject().put("success", true);
                }
            });
        }
    }

    JSONObject activate(String skillId) {
        synchronized (LOCK) {
            return updateSkill(skillId, new SkillMutation() {
                @Override public JSONObject apply(JSONObject item) throws Exception {
                    JSONArray tasks = item.optJSONArray("verified_task_ids");
                    boolean verified = tasks != null && tasks.length() >= 2;
                    boolean candidate = "VERIFIED".equals(item.optString("status", ""))
                            || "CANDIDATE".equals(item.optString("status", ""));
                    if (!HelperCoachSkillLifecyclePolicy.canActivate(
                            candidate,
                            verified ? tasks.length() : 0,
                            item.optBoolean("regression_checks_passed", false))) {
                        return new JSONObject().put("success", false)
                                .put("error", !verified
                                        ? "NEEDS_INDEPENDENT_VERIFICATION"
                                        : "NEEDS_REGRESSION_CHECKS");
                    }
                    item.put("active", true).put("status", "ACTIVE")
                            .put("activated_at", System.currentTimeMillis());
                    return new JSONObject().put("success", true);
                }
            });
        }
    }

    JSONObject deactivate(String skillId) {
        synchronized (LOCK) {
            return updateSkill(skillId, new SkillMutation() {
                @Override public JSONObject apply(JSONObject item) throws Exception {
                    JSONArray tasks = item.optJSONArray("verified_task_ids");
                    boolean verified = tasks != null && tasks.length() >= 2;
                    item.put("active", false)
                            .put("status", verified ? "VERIFIED" : "CANDIDATE")
                            .put("updated_at", System.currentTimeMillis());
                    return new JSONObject().put("success", true);
                }
            });
        }
    }

    JSONObject rollback(String skillId) {
        synchronized (LOCK) {
            JSONObject out = new JSONObject();
            try {
                JSONArray all = loadSkills();
                int index = indexOfSkill(all, safeId(skillId));
                if (index < 0) return out.put("success", false).put("error", "SKILL_NOT_FOUND");
                JSONObject item = all.optJSONObject(index);
                JSONArray history = cloneArray(item.optJSONArray("history"));
                if (!HelperCoachSkillLifecyclePolicy.canRollback(history.length() > 0)) {
                    return out.put("success", false).put("error", "NO_PREVIOUS_VERSION");
                }
                JSONObject current = new JSONObject(item.toString());
                current.remove("history");
                JSONObject prior = history.optJSONObject(history.length() - 1);
                history.remove(history.length() - 1);
                prior.remove("history");
                prior.put("history", history)
                        .put("rolled_back_from_version", current.optInt("version", 0))
                        .put("updated_at", System.currentTimeMillis());
                if (prior.optBoolean("active", false)) prior.put("status", "ACTIVE");
                else prior.put("status", prior.optString("status", "CANDIDATE"));
                all.put(index, prior);
                saveSkills(all);
                return out.put("success", true)
                        .put("skill_id", skillId)
                        .put("version", prior.optInt("version", 1));
            } catch (Exception error) {
                try { return out.put("success", false).put("error", "SKILL_STORE_ERROR"); }
                catch (Exception ignored) { return new JSONObject(); }
            }
        }
    }

    JSONObject stageCodeProposal(JSONObject request) {
        synchronized (LOCK) {
            JSONObject out = new JSONObject();
            try {
                if (prefs == null || request == null) {
                    return out.put("success", false).put("error", "STORE_UNAVAILABLE");
                }
                String repository = request.optString("repository", "").trim();
                String ref = request.optString("proposed_diff_ref", "").trim();
                if (!"magic76/crew-helper".equalsIgnoreCase(repository)
                        || !ref.matches("https://github\\.com/magic76/crew-helper/(pull/[1-9][0-9]*|commit/[0-9a-fA-F]{7,40})/?")) {
                    return out.put("success", false).put("error", "INVALID_CODE_PROPOSAL_REF");
                }
                String title = CoachDataRedactor.safeFreeText(
                        request.optString("title", "Helper code proposal"), 120);
                JSONObject item = new JSONObject()
                        .put("proposal_id", UUID.randomUUID().toString())
                        .put("repository", "magic76/crew-helper")
                        .put("title", title.isEmpty() ? "Helper code proposal" : title)
                        .put("diagnosis", CoachDataRedactor.safeFreeText(
                                request.optString("diagnosis", ""), 240))
                        .put("reproduction_steps", cleanStringArray(
                                request.optJSONArray("reproduction_steps"), 12, 160))
                        .put("test_plan", cleanStringArray(
                                request.optJSONArray("test_plan"), 12, 160))
                        .put("proposed_diff_ref", ref)
                        .put("status", "PENDING_REVIEW")
                        .put("received_at", System.currentTimeMillis());
                JSONArray proposals = loadProposals();
                proposals.put(item);
                saveProposals(trim(proposals));
                return out.put("success", true).put("status", "PENDING_REVIEW")
                        .put("proposal_id", item.optString("proposal_id", ""));
            } catch (Exception error) {
                try { return out.put("success", false).put("error", "PROPOSAL_STORE_ERROR"); }
                catch (Exception ignored) { return new JSONObject(); }
            }
        }
    }

    JSONArray listSkills() { synchronized (LOCK) { return cloneArray(loadSkills()); } }
    JSONArray listCodeProposals() { synchronized (LOCK) { return cloneArray(loadProposals()); } }

    JSONArray activeSkillSummaries(String appPackage) {
        synchronized (LOCK) {
            JSONArray result = new JSONArray();
            String pkg = appPackage == null ? "" : appPackage.trim();
            if (!validPackage(pkg)) return result;
            JSONArray all = loadSkills();
            for (int i = 0; i < all.length() && result.length() < 16; i++) {
                JSONObject item = all.optJSONObject(i);
                if (item == null || !item.optBoolean("active", false)
                        || !pkg.equals(item.optString("app_package", ""))) continue;
                JSONArray actions = item.optJSONArray("action_sequence");
                JSONArray safeActions = new JSONArray();
                if (actions != null) {
                    for (int j = 0; j < actions.length(); j++) {
                        String action = actions.optString(j, "");
                        if (HelperCoachRuntimePolicy.ACTION_INSPECT_UI.equals(action)
                                || HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP.equals(action)) {
                            safeActions.put(action);
                        }
                    }
                }
                try {
                    result.put(new JSONObject()
                            .put("skill_id", safeId(item.optString("skill_id", "")))
                            .put("version", Math.max(1, item.optInt("version", 1)))
                            .put("action_sequence", safeActions));
                } catch (Exception ignored) {}
            }
            return result;
        }
    }

    private interface SkillMutation { JSONObject apply(JSONObject item) throws Exception; }

    private JSONObject updateSkill(String skillId, SkillMutation mutation) {
        JSONObject out = new JSONObject();
        try {
            JSONArray all = loadSkills();
            JSONObject item = findSkill(all, safeId(skillId));
            if (item == null) return out.put("success", false).put("error", "SKILL_NOT_FOUND");
            JSONObject result = mutation.apply(item);
            if (!result.optBoolean("success", false)) return result;
            saveSkills(all);
            return result.put("skill_id", skillId)
                    .put("status", item.optString("status", "CANDIDATE"))
                    .put("active", item.optBoolean("active", false));
        } catch (Exception error) {
            try { return out.put("success", false).put("error", "SKILL_STORE_ERROR"); }
            catch (Exception ignored) { return new JSONObject(); }
        }
    }

    private JSONArray loadSkills() { return load(KEY_SKILLS); }
    private JSONArray loadProposals() { return load(KEY_PROPOSALS); }
    private JSONArray load(String key) {
        if (prefs == null) return new JSONArray();
        try { return new JSONArray(prefs.getString(key, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
    private void saveSkills(JSONArray value) { save(KEY_SKILLS, value); }
    private void saveProposals(JSONArray value) { save(KEY_PROPOSALS, value); }
    private void save(String key, JSONArray value) {
        if (prefs != null) prefs.edit().putString(key, value.toString()).commit();
    }

    private static JSONObject findSkill(JSONArray all, String id) {
        int index = indexOfSkill(all, id);
        return index < 0 ? null : all.optJSONObject(index);
    }
    private static int indexOfSkill(JSONArray all, String id) {
        if (all == null || id.isEmpty()) return -1;
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && id.equals(item.optString("skill_id", ""))) return i;
        }
        return -1;
    }

    private static JSONArray cleanStringArray(JSONArray source, int maxCount, int maxLength) {
        if (source == null) return null;
        if (source.length() > maxCount) return null;
        JSONArray out = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            String item = CoachDataRedactor.safeFreeText(source.optString(i, ""), maxLength);
            if (item.isEmpty()) return null;
            out.put(item);
        }
        return out;
    }

    private static JSONArray cloneArray(JSONArray source) {
        try { return source == null ? new JSONArray() : new JSONArray(source.toString()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONArray trim(JSONArray source) {
        JSONArray out = new JSONArray();
        int start = Math.max(0, source.length() - MAX_ITEMS);
        for (int i = start; i < source.length(); i++) out.put(source.opt(i));
        return out;
    }

    private static String safeId(String value) {
        String id = value == null ? "" : value.trim();
        return id.matches("[A-Za-z0-9_.:-]{1,80}") ? id : "";
    }

    private static boolean validPackage(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

}
