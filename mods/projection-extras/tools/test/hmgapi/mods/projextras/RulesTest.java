package hmgapi.mods.projextras;

public final class RulesTest {
    static int fail, checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            fail++;
            System.out.println("FAIL  " + what);
        }
    }

    public static void main(String[] a) {
        Rules r = new Rules();
        r.asked("AA", 1000);
        check(!r.answer("other", 2000), "an answer about another phone isn't ours");
        check(r.answer("AA", 3000), "our answer, in time");
        check(!r.answer("AA", 4000), "it counts once");
        r.asked("AA", 0);
        check(!r.answer("AA", Rules.ASK_MS + 1), "too late");
        check(!r.answer("AA", Rules.ASK_MS + 2), "and forgotten");
        r.asked("AA", 0);
        r.carPlayClosed();
        check(!r.answer("AA", 10), "CarPlay closed: no question open");

        check(r.sendDisconnect(0), "first disconnect goes");
        check(!r.sendDisconnect(Rules.DISCONNECT_GAP_MS - 1), "not again so soon");
        check(r.retry(Rules.RETRY_WINDOW_MS), "retries inside the window");
        check(!r.retry(Rules.RETRY_WINDOW_MS + 1), "then gives up");
        check(r.sendDisconnect(Rules.DISCONNECT_GAP_MS), "after the gap it goes again");
        check(!new Rules().retry(0), "no retry without a disconnect");

        check(r.holdAutoStart("P", Rules.ACC_DELAY), "car off holds automatic starts");
        check(r.holdAutoStart("P", 0xff), "car fully off too");
        check(!r.holdAutoStart("P", 0), "car on: starts");
        r.aaEndedOnHeadUnit("P");
        check(r.holdAutoStart("P", 0), "ended on the head unit: held");
        check(!r.holdAutoStart("Q", 0), "another phone isn't held");
        r.aaButton("P");
        check(!r.holdAutoStart("P", 0), "its button brings it back");
        r.aaEndedOnHeadUnit("P");
        r.carOff();
        check(!r.holdAutoStart("P", 0), "a new drive starts fresh");
        check(Rules.carOffStatus(6) && Rules.carOffStatus(0xff) && !Rules.carOffStatus(0) && !Rules.carOffStatus(5), "power statuses");

        System.out.println(fail == 0 ? "ok    projection switch rules: " + checks + " checks" : "FAILED");
        if (fail > 0) System.exit(1);
    }
}
