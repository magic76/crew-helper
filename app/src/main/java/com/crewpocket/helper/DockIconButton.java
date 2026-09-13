package com.crewpocket.helper;

import android.content.Context;

/**
 * 0111: Live Console icon button backed by the shared Crew icon renderer.
 * Public constants/API stay stable so FloatingBubbleManager does not change.
 */
final class DockIconButton extends CrewIconView {
    public static final int ICON_CAMERA = 1;
    public static final int ICON_SCREEN = 2;
    public static final int ICON_MIC_ACTIVE = 3;
    public static final int ICON_MIC_MUTED = 4;
    public static final int ICON_SPEAKER = 5;
    public static final int ICON_CALL_START = 6;
    public static final int ICON_CALL_HANGUP = 7;

    public DockIconButton(Context context) {
        super(context);
        setIconScale(0.68f);
    }

    public void setIcon(int type, int color) {
        int shared;
        switch (type) {
            case ICON_CAMERA: shared = CrewIcons.CAMERA; break;
            case ICON_SCREEN: shared = CrewIcons.SCREEN; break;
            case ICON_MIC_MUTED: shared = CrewIcons.MIC_MUTED; break;
            case ICON_SPEAKER:
                // This control interrupts current AI speech; show the action,
                // not a generic speaker/volume symbol.
                shared = CrewIcons.INTERRUPT;
                break;
            case ICON_CALL_START: shared = CrewIcons.VOICE; break;
            case ICON_CALL_HANGUP: shared = CrewIcons.HANGUP; break;
            case ICON_MIC_ACTIVE:
            default: shared = CrewIcons.MIC; break;
        }
        super.setIcon(shared, color);
    }
}
