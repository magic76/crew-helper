package com.crewpocket.helper;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.security.MessageDigest;
import java.util.Locale;
/** Stable identity for correction matching; deliberately ignores volatile body text. */
final class StableScreenKey {
    private static final int MAX_VISITED_NODES = 160, MAX_INCLUDED_NODES = 72, COARSE_GRID_PX = 96;
    private StableScreenKey() {}
    static String create(AccessibilityNodeInfo root) {
        if (root == null) return "";
        StringBuilder out = new StringBuilder(String.valueOf(root.getPackageName())).append('|');
        append(root, out, new int[]{0}, new int[]{0}, 0);
        return "stable_v2:" + hash(out.toString());
    }
    private static void append(AccessibilityNodeInfo n, StringBuilder out, int[] seen, int[] kept, int depth) {
        if (n == null || seen[0]++ >= MAX_VISITED_NODES) return;
        String id = clean(n.getViewIdResourceName()), cls = clean(n.getClassName());
        boolean stable = n.isEditable() || n.isScrollable() || cls.contains("button") || cls.contains("switch") || id.matches(".*(toolbar|appbar|navigation|nav_|menu|back|search|send|submit|composer|input|tab|action_|fab).*");
        if ((depth <= 2 || stable || n.isScrollable()) && kept[0]++ < MAX_INCLUDED_NODES) {
            Rect b = new Rect(); n.getBoundsInScreen(b); String label = "";
            if (!SensitiveDataGuard.isSensitiveNode(n) && (depth <= 2 || stable)) { CharSequence t=n.getText(); if (t==null||t.length()==0) t=n.getContentDescription(); label=clean(t).replaceAll("[0-9０-９]+", "#"); }
            out.append(cls).append(':').append(clean(id).replaceAll("[0-9０-９]+", "#")).append(':').append(label).append(':').append(n.isClickable()?1:0).append(n.isEditable()?1:0).append(n.isScrollable()?1:0).append(':').append(b.left/COARSE_GRID_PX).append(',').append(b.top/COARSE_GRID_PX).append('|');
        }
        for (int i=0;i<n.getChildCount()&&seen[0]<MAX_VISITED_NODES&&kept[0]<MAX_INCLUDED_NODES;i++) { AccessibilityNodeInfo c=n.getChild(i); if(c!=null) try { append(c,out,seen,kept,depth+1); } finally { c.recycle(); } }
    }
    private static String clean(Object v) { String s=v==null?"":String.valueOf(v).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim(); return s.length()>96?s.substring(0,96):s; }
    private static String hash(String s) { try { byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")); StringBuilder h=new StringBuilder(); for(int i=0;i<12;i++) h.append(String.format(Locale.ROOT,"%02x",b[i]&255)); return h.toString(); } catch(Exception e) { return Integer.toHexString(s.hashCode()); } }
}
