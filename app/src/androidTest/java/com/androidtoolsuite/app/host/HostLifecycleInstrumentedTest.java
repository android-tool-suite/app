package com.androidtoolsuite.app.host;

import static org.junit.Assert.assertFalse;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class HostLifecycleInstrumentedTest {
    @Test
    public void coldStartBackgroundForegroundAndRecreationRemainUsable() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.recreate();
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
        }
    }
}
