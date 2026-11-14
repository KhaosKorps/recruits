package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KhaosKorps fork: Nena's M1 (AUDT-261113-Recruits_Test_Audit_And_Test_Plan_By_Nena).
 * Every pathfinding setting's default reproduces the 1.15.2 release, except the two the user
 * chose to change (DRFT-261113-Recruits_Follow_Pathfinding_Settings_And_Measurement, section 8,
 * decisions 1 and 3): waiting back from the edge when the owner is out of reach, and the edge margin.
 */
class FollowSettingsDefaultsTest {

    private final FollowSettings d = FollowSettings.DEFAULTS;

    @Test
    void fallDistancesDefaultToTheReleasesOne() {
        assertEquals(1, d.maxFallDistance(), "AbstractRecruitEntity.java:153");
        assertEquals(1, d.mountedMaxFallDistance(), "MobMixin.java:33");
    }

    @Test
    void followDistancesDefaultToTheReleasesSquaredNineAndSeven() {
        assertEquals(9.0, d.followStartDistanceSqr(), 1e-9, "RecruitFollowOwnerGoal.java:23");
        assertEquals(7.0, d.followStopDistanceSqr(), 1e-9, "RecruitFollowOwnerGoal.java:24");
    }

    @Test
    void followTimingAndSpeedDefaultToTheRelease() {
        assertEquals(10, d.followRepathTicks(), "RecruitFollowOwnerGoal.java:73");
        assertEquals(5, d.followRepathTicksRiding(), "RecruitFollowOwnerGoal.java:73");
        assertEquals(10, d.followStartCheckTicks(), "RecruitFollowOwnerGoal.java:29");
        assertEquals(1.05, d.followSpeed(), 1e-9, "AbstractRecruitEntity.java:346");
    }

    @Test
    void searchDefaultsToTheRelease() {
        assertEquals(0, d.followMaxPathLength(), "0 means each recruit's own follow range, as AsyncPathNavigation.java:85");
        assertEquals(16, d.searchNodesPerBlock(), "AsyncPathNavigation.java:37");
        assertEquals(FollowSettings.DirtPathPreference.ORIGINAL, d.dirtPathPreference(), "RecruitsPathNodeEvaluator.java:73-106");
        assertFalse(d.keepWalkingWhileRepathing(), "K1 fix off: the release swaps paths at once");
        assertFalse(d.staggerFollowRepaths(), "K3 fix off: the release starts every follower at 0");
    }

    @Test
    void speedMultipliersDefaultToOne() {
        assertEquals(1.0, d.movementSpeedMultiplier(), 1e-9);
        assertEquals(1.0, d.swimSpeedMultiplier(), 1e-9);
    }

    @Test
    void creepersStillIgnoreRecruitsByDefault() {
        assertFalse(d.creepersTargetRecruits(), "PillagerEvents.java:63-65 leaves creepers out");
    }

    @Test
    void theTwoSafetyBehavioursTheUserChoseAreOnByDefault() {
        assertTrue(d.edgeMargin(), "decision 3: the edge margin is on by default");
        assertTrue(d.waitWhenOwnerUnreachable(), "decision 1: wait back from the edge when the owner is out of reach");
    }

    @Test
    void pathLoggingIsOffByDefault() {
        assertFalse(d.pathLog());
    }
}
