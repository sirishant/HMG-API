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

    /** the shape of the iAP2 server's Iap2NotiMsg that wirelessStatus reads */
    public static final class Item {
        final int id;
        final int[] v;

        Item(int id, int... v) {
            this.id = id;
            this.v = v;
        }

        public int getItemId() {
            return id;
        }

        public int[] getItemIntArray() {
            return v;
        }
    }

    public static final class Group {
        final Item[] items;

        Group(Item[] items) {
            this.items = items;
        }

        public int getItemCnt() {
            return items.length;
        }

        public Item getObjItem(int k) {
            return items[k];
        }
    }

    public static final class Msg {
        final Group g;

        Msg(Item... items) {
            g = new Group(items);
        }

        public Group getItemGroup() {
            return g;
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

        String popup = "com.daudio.app.phoneprojection.wireless.popup.WirelessAlertDialogActivity";
        check(Rules.behindPopup("com.daudio.app.dipo.ui.DipoActivity", popup), "CarPlay's screen over the popup: held back");
        check(!Rules.behindPopup("com.daudio.app.dipo.ui.DipoActivity", "com.hkmc.launcher.Launcher"), "no popup: it opens");
        check(!Rules.behindPopup("com.daudio.app.dipo.ui.DipoActivity", null), "top unknown: it opens");
        check(!Rules.behindPopup("com.daudio.app.dipo.ui.SiriActivity", popup), "another screen: not ours to hold");
        check(!Rules.behindPopup(null, popup), "no component: not ours to hold");

        check(Rules.wirelessStatus(new Msg(new Item(0, 1))) == 1, "wireless CarPlay available");
        check(Rules.wirelessStatus(new Msg(new Item(2, 7), new Item(0, 0))) == 0, "not available, after another item");
        check(Rules.wirelessStatus(new Msg(new Item(1, 1))) == -1, "no status item: doesn't say");
        check(Rules.wirelessStatus(new Msg(new Item(0))) == -1, "empty status: doesn't say");
        check(Rules.wirelessStatus(new Msg((Item) null)) == -1, "unreadable item: doesn't say");
        check(Rules.wirelessStatus(null) == -1 && Rules.wirelessStatus("x") == -1, "no message or another kind: doesn't say");

        System.out.println(fail == 0 ? "ok    projection switch rules: " + checks + " checks" : "FAILED");
        if (fail > 0) System.exit(1);
    }
}
