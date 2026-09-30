package com.hoangnt391.automessenger;
import android.accessibilityservice.AccessibilityService;
import android.os.*;
import android.view.accessibility.*;
public class MessageAccessibilityService extends AccessibilityService {
    private long last;
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || System.currentTimeMillis()-last < 1500) return;
        if (e.getEventType()!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && e.getEventType()!=AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return;
        if (!getSharedPreferences("AutoMessenger",0).getBoolean("enabled",false)) return;
        last=System.currentTimeMillis();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return;
        AccessibilityNodeInfo input=findEditable(root);
        if(input!=null) { /* AI pipeline hook: screen text -> model -> set text */ }
    }
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n){
        if(n.isEditable()) return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){AccessibilityNodeInfo r=findEditable(c);if(r!=null)return r;}}
        return null;
    }
    @Override public void onInterrupt(){}
}