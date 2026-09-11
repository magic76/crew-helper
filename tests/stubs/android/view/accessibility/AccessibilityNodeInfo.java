package android.view.accessibility;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;
public class AccessibilityNodeInfo {
    private boolean visible=true, enabled=true, clickable, editable, scrollable;
    private CharSequence text="", desc="", viewId="", className="android.view.View";
    private Rect bounds=new Rect(0,0,100,100);
    private AccessibilityNodeInfo parent;
    private final List<AccessibilityNodeInfo> children = new ArrayList<AccessibilityNodeInfo>();
    public static AccessibilityNodeInfo obtain(AccessibilityNodeInfo n) { return n; }
    public boolean isVisibleToUser(){return visible;} public boolean isEnabled(){return enabled;}
    public boolean isClickable(){return clickable;} public boolean isEditable(){return editable;}
    public boolean isScrollable(){return scrollable;}
    public CharSequence getText(){return text;} public CharSequence getContentDescription(){return desc;}
    public CharSequence getViewIdResourceName(){return viewId;} public CharSequence getClassName(){return className;}
    public void getBoundsInScreen(Rect out){out.left=bounds.left;out.top=bounds.top;out.right=bounds.right;out.bottom=bounds.bottom;}
    public AccessibilityNodeInfo getParent(){return parent;} public int getChildCount(){return children.size();}
    public AccessibilityNodeInfo getChild(int i){return children.get(i);} public void recycle(){}
}
