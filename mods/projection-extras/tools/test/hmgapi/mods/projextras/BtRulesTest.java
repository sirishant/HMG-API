package hmgapi.mods.projextras;

public final class BtRulesTest {
    static int fail, checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            fail++;
            System.out.println("FAIL  " + what);
        }
    }

    /** one look: connected phones, busy, CarPlay, paired */
    static int look(BtRules r, int connected, long now) {
        return r.tick(connected, false, false, 2, now);
    }

    public static void main(String[] a) {
        long t = 0;
        BtRules r = new BtRules();

        // nothing until a phone has been on Bluetooth
        check(look(r, 0, t) == BtRules.IDLE, "no phone yet: idle");
        check(!r.isArmed(), "not armed before a phone");
        check(r.delay() == BtRules.FAST_MS, "looks every 15 s");
        check(look(r, 1, t += 15000) == BtRules.ARMED, "a phone connects: armed");
        check(look(r, 1, t += 15000) == BtRules.IDLE, "still connected: idle");

        // the phone drops off: 20 tries 15 s apart, then 60 s, then 3 min
        for (int i = 1; i <= BtRules.FAST_TRIES; i++) {
            check(look(r, 0, t += r.delay()) == BtRules.RECONNECT, "try " + i + " reconnects");
            check(r.tries() == i, "tries counted: " + r.tries());
        }
        check(r.delay() == BtRules.MID_MS, "after 20 tries: every 60 s");
        while (r.tries() < BtRules.MID_TRIES) check(look(r, 0, t += r.delay()) == BtRules.RECONNECT, "mid try");
        check(r.delay() == BtRules.SLOW_MS, "after 45 tries: every 3 min");
        check(look(r, 0, t += r.delay()) == BtRules.RECONNECT, "keeps trying at 3 min");
        check(look(r, 1, t += r.delay()) == BtRules.BACK, "the phone is back");
        check(r.lastTries() == BtRules.MID_TRIES + 1, "back after its tries: " + r.lastTries());
        check(r.tries() == 0 && r.delay() == BtRules.FAST_MS, "back: counting starts over, 15 s");
        check(look(r, 1, t += 15000) == BtRules.IDLE, "connected again: idle, no second 'back'");

        // busy, nothing paired, CarPlay: no try, not counted
        check(r.tick(0, true, false, 2, t += 15000) == BtRules.IDLE && r.tries() == 0, "manager busy: no try");
        check(r.tick(0, false, false, 0, t += 15000) == BtRules.IDLE && r.tries() == 0, "nothing paired: no try");
        check(look(r, 0, t += 15000) == BtRules.RECONNECT, "then a try");
        check(r.tick(0, false, true, 2, t += 15000) == BtRules.IDLE, "CarPlay runs (it keeps the iPhone off Bluetooth): idle");
        check(r.tries() == 0, "CarPlay: counting starts over");

        // held by the driver: no tries until a phone connects again by itself, after one was gone
        r = new BtRules();
        look(r, 1, t += 15000);
        check(r.hold(), "a disconnect on the car's screen holds");
        check(!r.hold(), "a second one isn't new");
        check(look(r, 1, t += 15000) == BtRules.IDLE && r.isHeld(), "still connected (the hold came first): held");
        check(look(r, 0, t += 15000) == BtRules.IDLE, "gone while held: no try");
        check(look(r, 0, t += 15000) == BtRules.IDLE, "still no try");
        check(look(r, 1, t += 15000) == BtRules.RELEASED, "a phone connects by itself: hold over");
        check(!r.isHeld(), "not held");
        check(look(r, 0, t += 15000) == BtRules.RECONNECT, "it drops off again: tries");

        // CarPlay ended on the head unit with the iPhone still on Bluetooth: held through its drop
        r = new BtRules();
        look(r, 1, t += 15000);
        r.hold();
        check(look(r, 1, t += 15000) == BtRules.IDLE && r.isHeld(), "connected after the hold: still held");
        check(look(r, 0, t += 15000) == BtRules.IDLE, "the iPhone drops: not brought back");

        // a connect by hand ends a hold
        check(r.connectedByHand(), "connect by hand: hold over");
        check(!r.connectedByHand(), "no hold to end the second time");
        check(look(r, 0, t += 15000) == BtRules.RECONNECT, "after that the watchdog tries again");

        // the car's power
        r = new BtRules();
        long on = t;
        r.carOn(on);
        check(look(r, 1, t += 15000) == BtRules.ARMED, "armed after the car came on");
        check(look(r, 0, on + 20000) == BtRules.IDLE, "within 30 s of the car coming on: the car's own auto-connect goes first");
        check(look(r, 0, on + BtRules.CAR_ON_GRACE_MS) == BtRules.RECONNECT, "after 30 s: tries");
        r.carOn(on + 40000);
        check(look(r, 0, on + 45000) == BtRules.RECONNECT, "another 'on' while on doesn't restart the grace or disarm");
        r.carOff();
        check(r.isCarOff() && !r.isArmed() && r.tries() == 0, "car off: disarmed, counting over");
        check(look(r, 0, t += 15000) == BtRules.IDLE, "car off: idle");
        check(look(r, 1, t += 15000) == BtRules.IDLE && !r.isArmed(), "car off: a phone doesn't arm it");
        r.hold();
        long on2 = t += 15000;
        r.carOn(on2);
        check(!r.isCarOff() && !r.isHeld(), "car on: a fresh drive, no hold");
        check(look(r, 0, on2 + 60000) == BtRules.IDLE, "car on, no phone yet this drive: idle");
        check(look(r, 1, on2 + 75000) == BtRules.ARMED, "a phone this drive: armed");

        System.out.println((fail == 0 ? "ok    " : "FAIL  ") + "Bluetooth watchdog rules: " + (checks - fail) + "/" + checks + " checks");
        if (fail != 0) System.exit(1);
    }
}
