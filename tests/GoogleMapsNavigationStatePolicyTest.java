package com.crewpocket.helper;

public final class GoogleMapsNavigationStatePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{important:[{label:'Start'},{label:'Directions'}]}"),
                "route planning with Start is not active navigation");

        check(GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{important:[{label:'Exit navigation'}]}"),
                "explicit exit-navigation control proves guidance mode");

        check(GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{important:[{label:'Re-center'},{label:'Route overview'},{label:'Voice guidance'}]}"),
                "multiple guidance controls prove active navigation");

        check(GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{important:[{label:'結束導航'}]}"),
                "Chinese active-navigation control is recognized");

        check(GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{important:[{label:'Route overview'},{label:'Mute'}]}"),
                "generic Maps sound control plus route overview proves guidance mode");

        check(GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{important:[{label:'重新置中'},{label:'沿路搜尋'}]}"),
                "Chinese route-search controls prove active navigation");

        check(GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{elements:[{label:'結束'},{label:'重新置中'}]}"),
                "short localized end label plus guidance control proves active navigation");

        check(GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{elements:[{label:'Exit'},{label:'Route overview'}]}"),
                "short English exit label plus guidance control proves active navigation");

        check(!GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{elements:[{label:'結束'}]}"),
                "generic short end label alone is insufficient");

        check(GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{important:[{label:'取消導航'}]}"),
                "Chinese cancel-navigation control is terminal guidance evidence");

        check(!GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.apple.android.music",
                        "{important:[{label:'Exit navigation'}]}"),
                "navigation cues from another app are ignored");

        check(!GoogleMapsNavigationStatePolicy.isActiveNavigationScreen(
                        "com.google.android.apps.maps",
                        "{important:[{label:'Route overview'}]}"),
                "one weak guidance cue is insufficient");

        System.out.println(
                "GoogleMapsNavigationStatePolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
