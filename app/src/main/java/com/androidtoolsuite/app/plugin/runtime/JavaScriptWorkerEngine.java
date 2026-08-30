package com.androidtoolsuite.app.plugin.runtime;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.javascriptengine.IsolateStartupParameters;
import androidx.javascriptengine.JavaScriptIsolate;
import androidx.javascriptengine.JavaScriptSandbox;
import androidx.javascriptengine.Message;
import androidx.javascriptengine.MessagePort;

import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;
import com.androidtoolsuite.runtime.contract.ContractLimits;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** API 26+ out-of-process JavaScript worker execution with a capability-checked message port. */
public final class JavaScriptWorkerEngine {
    private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;

    private JavaScriptWorkerEngine() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Api26Impl.isSupported();
    }

    public static JSONObject run(
            Context context,
            PluginPackageStore.InstalledPlugin installed,
            RuntimePluginManifest.BackgroundEntry entry,
            JSONObject input,
            CapabilityRouter router,
            String sessionId
    ) throws WorkerFailure {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw new WorkerFailure("NOT_SUPPORTED", "JavaScript workers require Android 8.0 or newer", false);
        }
        return Api26Impl.run(context, installed, entry, input, router, sessionId);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static final class Api26Impl {
        private static final Object SANDBOX_LOCK = new Object();
        private static final ExecutorService PORT_EXECUTOR = Executors.newCachedThreadPool();
        // The sandbox intentionally follows the application process lifetime and receives only applicationContext.
        @SuppressLint("StaticFieldLeak")
        private static JavaScriptSandbox sandbox;

        static boolean isSupported() {
            try {
                return JavaScriptSandbox.isSupported();
            } catch (RuntimeException error) {
                return false;
            }
        }

        @SuppressLint("RequiresFeature")
        static JSONObject run(
                Context context,
                PluginPackageStore.InstalledPlugin installed,
                RuntimePluginManifest.BackgroundEntry entry,
                JSONObject input,
                CapabilityRouter router,
                String sessionId
        ) throws WorkerFailure {
            if (!isSupported()) {
                throw new WorkerFailure("NOT_SUPPORTED", "The installed WebView does not provide JavaScriptSandbox", true);
            }
            long deadline = System.currentTimeMillis() + entry.timeoutMs;
            JavaScriptSandbox current = sandbox(context, deadline);
            if (!current.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)) {
                throw new WorkerFailure(
                        "NOT_SUPPORTED",
                        "JavaScriptSandbox lacks Promise return support",
                        true
                );
            }
            boolean messagePorts = current.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_MESSAGE_PORTS);
            IsolateStartupParameters parameters = new IsolateStartupParameters();
            if (current.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                parameters.setMaxHeapSizeBytes(entry.maxHeapBytes);
            }
            if (current.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
                parameters.setMaxEvaluationReturnSizeBytes(ContractLimits.MAX_RPC_BYTES);
            }
            JavaScriptIsolate isolate = null;
            MessagePort port = null;
            try {
                isolate = current.createIsolate(parameters);
                String transportBootstrap;
                if (messagePorts) {
                    AtomicReference<MessagePort> portReference = new AtomicReference<>();
                    AtomicInteger pending = new AtomicInteger();
                    port = isolate.createMessageChannel("ats", PORT_EXECUTOR, message -> {
                    MessagePort replyPort = portReference.get();
                    if (replyPort == null || message.getType() != Message.TYPE_STRING) return;
                    String raw = message.getString();
                    if (raw.getBytes(StandardCharsets.UTF_8).length > ContractLimits.MAX_RPC_BYTES) {
                        postError(replyPort, "", "RESOURCE_LIMIT", "Capability request exceeds 256 KiB", false);
                        return;
                    }
                    final JSONObject request;
                    final String id;
                    final String method;
                    final JSONObject payload;
                    try {
                        request = new JSONObject(raw);
                        Object rawId = request.opt("id");
                        Object rawMethod = request.opt("method");
                        id = rawId instanceof String ? ((String) rawId).trim() : "";
                        method = rawMethod instanceof String ? ((String) rawMethod).trim() : "";
                        payload = request.optJSONObject("payload");
                        if (id.isEmpty() || method.isEmpty() || payload == null) {
                            postError(replyPort, id, "INVALID_REQUEST", "Malformed capability request", false);
                            return;
                        }
                    } catch (JSONException error) {
                        postError(replyPort, "", "INVALID_REQUEST", "Capability request is not valid JSON", false);
                        return;
                    }
                    if (pending.incrementAndGet() > ContractLimits.MAX_PENDING_REQUESTS) {
                        pending.decrementAndGet();
                        postError(replyPort, id, "RESOURCE_LIMIT", "Too many pending capability calls", true);
                        return;
                    }
                    int remaining = (int) Math.max(1L, Math.min(
                            ContractLimits.MAX_DEADLINE_MS,
                            deadline - System.currentTimeMillis()
                    ));
                    router.invoke(
                            installed.manifest,
                            installed.manifest.plugin.id,
                            sessionId,
                            method,
                            payload,
                            false,
                            remaining
                    ).whenComplete((result, failure) -> {
                        pending.decrementAndGet();
                        if (failure == null) postResult(replyPort, id, result);
                        else postFailure(replyPort, id, failure);
                    });
                    });
                    portReference.set(port);
                    transportBootstrap = "const __pending=new Map();let __seq=0;"
                            + "const __port=await android.getNamedPort('ats');"
                            + "__port.onmessage=(event)=>{try{const m=JSON.parse(event.data);const p=__pending.get(m.id);"
                            + "if(!p)return;__pending.delete(m.id);if(m.error){const e=new Error(m.error.message||m.error.code);"
                            + "e.code=m.error.code;e.retryable=!!m.error.retryable;p.reject(e);}else p.resolve(m.result||{});}catch(_){}};"
                            + "const __call=(method,payload={})=>new Promise((resolve,reject)=>{"
                            + "const id='c'+(++__seq);__pending.set(id,{resolve,reject});"
                            + "__port.postMessage(JSON.stringify({id,method,payload}));});";
                } else {
                    transportBootstrap = "const __call=()=>{const e=new Error('JavaScriptSandbox message ports are unavailable');"
                            + "e.code='NOT_SUPPORTED';e.retryable=true;return Promise.reject(e);};";
                }

                String source = readSource(new File(installed.generationDirectory, entry.entry));
                await(isolate.evaluateJavaScriptAsync(source), deadline);
                String inputJson = input == null ? "{}" : input.toString();
                String bootstrap = "(async()=>{"
                        + transportBootstrap
                        + "const __b64=(value)=>{const chars='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';"
                        + "const clean=value.replace(/=+$/,'');const out=[];let bits=0,count=0;for(const ch of clean){const n=chars.indexOf(ch);"
                        + "if(n<0)throw new Error('invalid base64');bits=(bits<<6)|n;count+=6;if(count>=8){count-=8;out.push((bits>>count)&255);}}return new Uint8Array(out);};"
                        + "const __utf8=(bytes)=>{let out='';for(let i=0;i<bytes.length;){const a=bytes[i++];let cp;if(a<128)cp=a;"
                        + "else if((a&224)===192){cp=((a&31)<<6)|(bytes[i++]&63);}else if((a&240)===224){cp=((a&15)<<12)|((bytes[i++]&63)<<6)|(bytes[i++]&63);}"
                        + "else{cp=((a&7)<<18)|((bytes[i++]&63)<<12)|((bytes[i++]&63)<<6)|(bytes[i++]&63);}out+=String.fromCodePoint(cp);}return out;};"
                        + "const ats=Object.freeze({call:__call,decodeBase64:__b64,decodeUtf8:__utf8});"
                        + "const input=JSON.parse(" + JSONObject.quote(inputJson) + ");"
                        + "if(typeof globalThis.atsWorkerMain!=='function')throw new Error('workers entry must define globalThis.atsWorkerMain');"
                        + "try{const output=await globalThis.atsWorkerMain(input,ats);"
                        + "return JSON.stringify({ok:true,result:output==null?{}:output});}"
                        + "catch(error){return JSON.stringify({ok:false,error:{code:String(error?.code||'WORKER_FAILED'),"
                        + "message:String(error?.message||error||'JavaScript worker failed'),retryable:!!error?.retryable}});}})()";
                String rawResult = await(isolate.evaluateJavaScriptAsync(bootstrap), deadline);
                Object value = new org.json.JSONTokener(rawResult).nextValue();
                if (!(value instanceof JSONObject)) {
                    throw new WorkerFailure("INVALID_RESULT", "JavaScript worker must return a JSON object", false);
                }
                JSONObject envelope = (JSONObject) value;
                if (!envelope.optBoolean("ok", false)) {
                    JSONObject error = envelope.optJSONObject("error");
                    throw new WorkerFailure(
                            error == null ? "WORKER_FAILED" : error.optString("code", "WORKER_FAILED"),
                            error == null ? "JavaScript worker failed" : error.optString("message", "JavaScript worker failed"),
                            error != null && error.optBoolean("retryable", false)
                    );
                }
                JSONObject result = envelope.optJSONObject("result");
                if (result == null) {
                    throw new WorkerFailure("INVALID_RESULT", "JavaScript worker must return a JSON object", false);
                }
                return result;
            } catch (WorkerFailure failure) {
                throw failure;
            } catch (TimeoutException error) {
                throw new WorkerFailure("TIMEOUT", "JavaScript worker timed out", true);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                resetSandboxIfDead(cause);
                throw new WorkerFailure("WORKER_FAILED", safeMessage(cause), false);
            } catch (IOException | JSONException | RuntimeException error) {
                resetSandboxIfDead(error);
                throw new WorkerFailure("WORKER_FAILED", safeMessage(error), false);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new WorkerFailure("CANCELLED", "JavaScript worker was interrupted", true);
            } finally {
                if (port != null) port.close();
                if (isolate != null) isolate.close();
            }
        }

        private static JavaScriptSandbox sandbox(Context context, long deadline) throws WorkerFailure {
            synchronized (SANDBOX_LOCK) {
                if (sandbox != null) return sandbox;
                try {
                    long remaining = Math.max(1L, deadline - System.currentTimeMillis());
                    sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context.getApplicationContext())
                            .get(remaining, TimeUnit.MILLISECONDS);
                    return sandbox;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new WorkerFailure("CANCELLED", "JavaScriptSandbox connection interrupted", true);
                } catch (ExecutionException | TimeoutException | RuntimeException error) {
                    throw new WorkerFailure("PROVIDER_OFFLINE", "Cannot connect JavaScriptSandbox: " + safeMessage(error), true);
                }
            }
        }

        private static String await(
                com.google.common.util.concurrent.ListenableFuture<String> future,
                long deadline
        ) throws InterruptedException, ExecutionException, TimeoutException {
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            return future.get(remaining, TimeUnit.MILLISECONDS);
        }

        private static String readSource(File file) throws IOException {
            if (!file.isFile()) throw new IOException("JavaScript worker entry is missing");
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > MAX_SOURCE_BYTES) throw new IOException("JavaScript worker exceeds 2 MiB");
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }

        private static void postResult(MessagePort port, String id, JSONObject result) {
            try {
                post(port, new JSONObject().put("id", id).put("result", result == null ? new JSONObject() : result));
            } catch (JSONException ignored) {
            }
        }

        private static void postFailure(MessagePort port, String id, Throwable raw) {
            Throwable failure = raw;
            while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                    && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof CapabilityFailure) {
                CapabilityFailure capability = (CapabilityFailure) failure;
                postError(port, id, capability.code, capability.getMessage(), capability.retryable);
            } else {
                postError(port, id, "INTERNAL", safeMessage(failure), true);
            }
        }

        private static void postError(MessagePort port, String id, String code, String message, boolean retryable) {
            try {
                post(port, new JSONObject()
                        .put("id", id)
                        .put("error", new JSONObject()
                                .put("code", code)
                                .put("message", message)
                                .put("retryable", retryable)));
            } catch (JSONException ignored) {
            }
        }

        private static void post(MessagePort port, JSONObject message) {
            String raw = message.toString();
            if (raw.getBytes(StandardCharsets.UTF_8).length > ContractLimits.MAX_RPC_BYTES) {
                raw = "{\"id\":\"\",\"error\":{\"code\":\"RESOURCE_LIMIT\",\"message\":\"Capability response exceeds 256 KiB\",\"retryable\":false}}";
            }
            port.postMessage(Message.createStringMessage(raw));
        }

        private static void resetSandboxIfDead(Throwable error) {
            String name = error.getClass().getName();
            if (!name.contains("Sandbox") && !name.contains("IsolateTerminated")) return;
            synchronized (SANDBOX_LOCK) {
                if (sandbox != null) sandbox.close();
                sandbox = null;
            }
        }
    }

    public static final class WorkerFailure extends Exception {
        public final String code;
        public final boolean retryable;

        WorkerFailure(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
