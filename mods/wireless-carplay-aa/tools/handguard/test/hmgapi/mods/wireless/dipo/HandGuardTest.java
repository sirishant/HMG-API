package hmgapi.mods.wireless.dipo;

public final class HandGuardTest {
    static int fail, checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            fail++;
            System.out.println("FAIL  " + what);
        }
    }

    public static void main(String[] a) {
        String ip = "AA:BB:CC:DD:EE:01", other = "AA:BB:CC:DD:EE:02";

        HandGuard g = new HandGuard();
        check(g.check(ip, 0) == HandGuard.FREE, "nothing connected by hand: starts go ahead");
        g.connectedByHand(ip, 1000);
        check(g.check(other, 2000) == HandGuard.FREE, "another phone isn't held back");
        check(g.check(ip, 2000) == HandGuard.HELD, "the phone connected by hand: its first start is held back, and said");
        check(g.check(ip, 5000) == HandGuard.HELD_QUIET, "a second start in the window: held back, not said again");
        check(g.secondsLeft(5000) == 21, "seconds left: " + g.secondsLeft(5000));
        check(g.check(ip.toLowerCase(), 6000) == HandGuard.HELD_QUIET, "address case doesn't matter");
        check(g.check(ip, 1000 + HandGuard.WINDOW_MS - 1) != HandGuard.FREE, "still held just before the window ends");
        check(g.check(ip, 1000 + HandGuard.WINDOW_MS) == HandGuard.FREE, "free once the window is over");
        check(g.check(ip, 1000 + HandGuard.WINDOW_MS - 1) == HandGuard.FREE, "and stays free (the guard is spent)");
        check(g.secondsLeft(30000) == 0, "no time left once spent");

        // a second connect by hand starts a new window, and says it again
        g.connectedByHand(ip, 40000);
        check(g.check(ip, 41000) == HandGuard.HELD, "new window: said again");
        g.connectedByHand(other, 42000);
        check(g.check(ip, 43000) == HandGuard.FREE, "the latest phone connected by hand is the one held back");
        check(g.check(other, 43000) == HandGuard.HELD, "the other phone is held back now");

        g = new HandGuard();
        g.connectedByHand(null, 0);
        g.connectedByHand("", 0);
        check(g.check(ip, 1) == HandGuard.FREE, "no address: nothing held back");
        check(g.check(null, 1) == HandGuard.FREE, "no phone: nothing held back");

        check("xx:xx:xx:xx:EE:01".equals(HandGuard.mask(ip)), "mask keeps the last two bytes: " + HandGuard.mask(ip));
        check("(none)".equals(HandGuard.mask(null)), "mask of nothing");
        check("xx".equals(HandGuard.mask("AB")), "mask of a short string");

        System.out.println((fail == 0 ? "ok    " : "FAIL  ") + "manual connect guard: " + (checks - fail) + "/" + checks + " checks");
        if (fail != 0) System.exit(1);
    }
}
