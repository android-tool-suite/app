package com.androidtoolsuite.app.host;

import com.androidtoolsuite.app.IShellService;
import android.content.Context;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ShellUserService extends IShellService.Stub {
    public ShellUserService() {
    }

    public ShellUserService(Context context) {
    }

    @Override
    public String run(String[] command) throws RemoteException {
        try {
            Process process = new ProcessBuilder(command).start();
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RemoteException(stderr.isEmpty() ? "命令退出码 " + exitCode : stderr);
            }
            return stdout;
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteException("命令执行被中断");
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    private String readStream(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }
}
