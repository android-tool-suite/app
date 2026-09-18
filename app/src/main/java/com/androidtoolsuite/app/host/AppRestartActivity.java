package com.androidtoolsuite.app.host;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/** A short-lived, non-exported process restarts only this application's main process. */
public final class AppRestartActivity extends Activity {
    private static final String SOURCE_PID = "source_pid";

    static void restart(Activity activity) {
        activity.startActivity(new Intent(activity, AppRestartActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SOURCE_PID, Process.myPid()));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int sourcePid = getIntent().getIntExtra(SOURCE_PID, -1);
        if (sourcePid <= 0 || sourcePid == Process.myPid()) {
            finish();
            return;
        }
        ActivityManager manager = getSystemService(ActivityManager.class);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pid == sourcePid && process.uid == Process.myUid()
                        && getApplicationInfo().processName.equals(process.processName)) {
                    Process.killProcess(sourcePid);
                    break;
                }
            }
        }
        // Construct the destination here; no arbitrary cross-process launch Intent is accepted.
        Intent launch = Intent.makeRestartActivityTask(new ComponentName(this, MainActivity.class));
        startActivity(launch);
        finish();
        Process.killProcess(Process.myPid());
    }
}
