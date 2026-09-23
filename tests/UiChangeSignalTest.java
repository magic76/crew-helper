package com.crewpocket.helper;

public final class UiChangeSignalTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        UiChangeSignal signal = new UiChangeSignal();
        long startRevision = signal.revision();
        check(startRevision == 0L, "initial revision");

        Thread producer = new Thread(() -> {
            try { Thread.sleep(35L); } catch (InterruptedException ignored) {}
            signal.markChanged();
        });
        producer.start();

        long started = System.nanoTime();
        boolean changed = signal.awaitChange(startRevision, 400L);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        producer.join();

        check(changed, "wakes on UI change");
        check(signal.revision() == 1L, "revision increments");
        check(elapsedMs < 300L, "event wake avoids full timeout");

        started = System.nanoTime();
        boolean timedOut = signal.awaitChange(signal.revision(), 50L);
        elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        check(!timedOut, "times out without UI event");
        check(elapsedMs >= 30L, "timeout waits when UI is unchanged");

        long beforeHumanEdit = signal.revision();
        signal.markChanged(16, true);
        check(signal.lastEventType() == 16, "retains privacy-safe event type");
        check(signal.lastHumanTextEditRevision() > beforeHumanEdit,
                "marks human text edit revision without storing text");

        System.out.println("PASS UiChangeSignalTest: " + assertions + " checks");
    }
}
