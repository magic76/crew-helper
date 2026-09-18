package com.crewpocket.helper;

import java.util.List;

public final class ToolCallDispatchLedgerTest {
    private static int checks;

    public static void main(String[] args) {
        ToolCallDispatchLedger ledger = new ToolCallDispatchLedger();

        ToolCallDispatchLedger.Registration first =
                ledger.register("c1", "tap_screen", "{x:1}", 7L);
        check(first.accepted, "first call accepted");
        check(!first.coalesced, "first call not coalesced");
        check(first.signature.startsWith("7|tap_screen:"),
                "signature is generation scoped");

        ToolCallDispatchLedger.Registration sameId =
                ledger.register("c1", "tap_screen", "{x:1}", 7L);
        check(!sameId.accepted && sameId.duplicateCallId,
                "same transport call id ignored");

        ToolCallDispatchLedger.Registration duplicate =
                ledger.register("c2", "tap_screen", "{x:1}", 7L);
        check(!duplicate.accepted && duplicate.coalesced,
                "equivalent in-flight call coalesced");
        check(ledger.coalescedRecipientCount() == 1,
                "duplicate recipient retained for fanout");

        List<ToolCallDispatchLedger.Recipient> recipients =
                ledger.responseRecipients("c1", "tap_screen");
        check(recipients.size() == 2,
                "primary result fans out to duplicate call");
        check("c1".equals(recipients.get(0).id),
                "primary recipient remains first");
        check("c2".equals(recipients.get(1).id),
                "coalesced recipient follows primary");

        ToolCallDispatchLedger.Registration nextGeneration =
                ledger.register("c3", "tap_screen", "{x:1}", 8L);
        check(nextGeneration.accepted,
                "same action in a newer user generation is independent");

        ledger.releaseInFlight(first.signature);
        ledger.releaseInFlight(nextGeneration.signature);
        check(ledger.inFlightCount() == 0,
                "completed signatures leave in-flight set");

        ToolCallDispatchLedger.Registration repeatAfterCompletion =
                ledger.register("c4", "tap_screen", "{x:1}", 7L);
        check(repeatAfterCompletion.accepted,
                "same action may run again after prior call completed");

        ledger.resetForNewIntent();
        check(ledger.inFlightCount() == 0,
                "new intent clears in-flight dispatch state");

        ToolCallDispatchLedger.Registration oldIdAgain =
                ledger.register("c4", "tap_screen", "{x:1}", 9L);
        check(!oldIdAgain.accepted && oldIdAgain.duplicateCallId,
                "transport call ids stay de-duplicated across intent reset");

        System.out.println(
                "ToolCallDispatchLedgerTest passed "
                        + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
