package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckThrottleTest {

    private static List<Integer> dueCalls(CheckThrottle throttle, int interval, int stagger, int calls) {
        List<Integer> due = new ArrayList<>();
        for (int call = 0; call < calls; call++) if (throttle.due(interval, stagger)) due.add(call);
        return due;
    }

    @Test
    void anIntervalOfOneRunsEveryCall() {
        assertEquals(List.of(0, 1, 2, 3, 4), dueCalls(new CheckThrottle(), 1, 17, 5));
    }

    @Test
    void anIntervalOfFiveRunsEveryFifthCallStartingAtTheStagger() {
        assertEquals(List.of(2, 7, 12), dueCalls(new CheckThrottle(), 5, 7, 15));
    }

    @Test
    void differentSeedsSpreadTheirChecksOut() {
        List<Integer> firsts = new ArrayList<>();
        for (int id = 100; id < 105; id++) firsts.add(dueCalls(new CheckThrottle(), 5, id, 5).get(0));
        assertEquals(List.of(0, 1, 2, 3, 4), firsts);
    }

    @Test
    void intervalsInTicksCountGoalRunsEverySecondTick() {
        CheckThrottle every2 = new CheckThrottle(), every3 = new CheckThrottle(), every100 = new CheckThrottle();
        int runs2 = 0, runs3 = 0, runs100 = 0;
        for (int call = 0; call < 100; call++) {
            if (every2.dueEveryTicks(2, 9)) runs2++;
            if (every3.dueEveryTicks(3, 9)) runs3++;
            if (every100.dueEveryTicks(100, 9)) runs100++;
        }
        assertEquals(100, runs2, "2 ticks: every goal run");
        assertEquals(50, runs3, "3 ticks rounds up to every second goal run");
        assertEquals(2, runs100, "100 ticks: every 50 goal runs");
    }

    @Test
    void aShorterIntervalTakesEffectAtOnce() {
        CheckThrottle throttle = new CheckThrottle();
        dueCalls(throttle, 50, 40, 3);
        List<Integer> after = dueCalls(throttle, 2, 40, 6);
        assertTrue(after.size() >= 3, "after shortening the interval to 2, checks ran on calls " + after);
    }
}
