package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.androidtoolsuite.app.plugin.runtime.HostServices;
import com.androidtoolsuite.app.plugin.runtime.HostTool;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class HostNavigationInstrumentedTest {
    @Test public void relatedToolReturnsToSourceAndHiddenSessionCannotCloseForeground() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                FixtureTool source = new FixtureTool("test.source");
                FixtureTool target = new FixtureTool("test.target");
                List<HostTool> tools = tools(activity);
                tools.add(source); tools.add(target);
                try {
                    activity.navigateForUi(1);
                    activity.openPluginForUi(source);
                    assertTrue(activity.openRelatedToolForUi(target.id(), "main"));
                    assertTrue(activity.hasPluginReturnTargetForUi());
                    activity.closePluginSessionForUi(source.id(), source.id());
                    assertSame(target, activity.selectedPluginForUi());
                    activity.closePluginSessionForUi(target.id(), "stale-session");
                    assertSame(target, activity.selectedPluginForUi());
                    activity.closePluginSessionForUi(target.id(), target.id());
                    assertSame(source, activity.selectedPluginForUi());
                    assertFalse(activity.hasPluginReturnTargetForUi());
                    activity.closePluginForUi();
                    assertEquals(1, activity.currentSectionForUi());
                } finally { activity.navigateForUi(0); tools.remove(source); tools.remove(target); }
            });
        }
    }

    @Test public void internalBackIsConsumedBeforeClosingToolAndManagementPreservesSource() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                FixtureTool source = new FixtureTool("test.back");
                List<HostTool> tools = tools(activity); tools.add(source);
                try {
                    activity.openPluginForUi(source);
                    source.childPage = true;
                    activity.handleBackForUi();
                    assertSame(source, activity.selectedPluginForUi());
                    assertFalse(source.childPage);
                    activity.showPluginManagementForUi(source.id(), "detail");
                    assertFalse(activity.isActivePluginSessionForUi(source.id(), source.id()));
                    activity.handleBackForUi();
                    assertNull(activity.managedPluginIdForUi());
                    assertSame(source, activity.selectedPluginForUi());
                    activity.handleBackForUi();
                    assertNull(activity.selectedPluginForUi());
                } finally { activity.navigateForUi(0); tools.remove(source); }
            });
        }
    }

    @SuppressWarnings("unchecked") private static List<HostTool> tools(MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("plugins"); field.setAccessible(true);
            return (List<HostTool>) field.get(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static final class FixtureTool implements HostTool {
        private final String id;
        boolean childPage;
        FixtureTool(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public String title() { return id; }
        @Override public String description() { return "Navigation test fixture"; }
        @Override public boolean removable() { return false; }
        @Override public boolean ownsSession(String session) { return id.equals(session); }
        @Override public boolean onBackRequested() { boolean handled = childPage; childPage = false; return handled; }
        @Override public View createView(Activity activity, HostServices host) { return new TextView(activity); }
        @Override public void onSelected() { }
        @Override public void onHostStateChanged() { }
        @Override public void onDestroy() { }
    }
}
