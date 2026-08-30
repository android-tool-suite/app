export * from "./generated";

import { RPC_PROTOCOL, type AtsErrorCode, type AtsEvent, type AtsMethod, type CapabilityId } from "./generated";

/** Built-in methods retain autocomplete; plugin-defined capabilities use a namespaced dotted method. */
export type AtsCapabilityMethod = AtsMethod | `${string}.${string}`;

export interface AtsWorkerApi {
  call<T = unknown>(method: AtsCapabilityMethod, payload?: unknown): Promise<T>;
  decodeBase64(value: string): Uint8Array;
  decodeUtf8(value: Uint8Array): string;
}

export interface AtsWorkerCapabilityInput {
  kind: "capability";
  capability: string;
  method: AtsCapabilityMethod;
  payload: Record<string, unknown>;
  caller: {
    pluginId: string;
    sessionId: string;
    scopes: Record<string, unknown>;
    userGesture: boolean;
  };
}

export type AtsWorkerMain = (
  input: AtsWorkerCapabilityInput | Record<string, unknown>,
  ats: AtsWorkerApi,
) => Promise<Record<string, unknown>> | Record<string, unknown>;

export interface AtsPermissionSnapshot {
  capability: CapabilityId;
  title: string;
  risk: "normal" | "sensitive" | "restricted";
  state: "granted" | "pending" | "denied";
  optional: boolean;
}

export interface AtsReadyPayload {
  platform: { id: string; api: number };
  protocol: string;
  capabilities: string[];
  permissions: AtsPermissionSnapshot[];
  theme: { dark: boolean; css: string };
  container: { widthDp: number; heightDp: number };
}

export interface AtsRpcErrorShape {
  code: AtsErrorCode;
  message: string;
  retryable: boolean;
  details?: unknown;
}

export class AtsRpcError extends Error {
  readonly code: AtsErrorCode;
  readonly retryable: boolean;
  readonly details?: unknown;

  constructor(error: AtsRpcErrorShape) {
    super(error.message);
    this.name = "AtsRpcError";
    this.code = error.code;
    this.retryable = error.retryable;
    this.details = error.details;
  }
}

interface NativeTransport {
  postMessage(message: string): void;
  onmessage: ((event: MessageEvent<string>) => void) | null;
}

interface RpcBase {
  protocol: string;
  kind: "hello" | "ready" | "request" | "response" | "event" | "cancel";
  pluginId: string;
  sessionId: string;
  requestId: string;
}

interface RpcResponse extends RpcBase {
  kind: "response";
  ok: boolean;
  result?: unknown;
  error?: AtsRpcErrorShape;
}

interface RpcReady extends RpcBase {
  kind: "ready";
  payload: unknown;
}

interface RpcEvent extends RpcBase {
  kind: "event";
  event: AtsEvent;
  sequence: number;
  payload: unknown;
}

interface Pending {
  resolve(value: unknown): void;
  reject(reason: unknown): void;
  timeout: number;
}

export interface AtsClientOptions {
  pluginId: string;
  sessionId: string;
  transport?: NativeTransport;
}

declare global {
  interface Window {
    atsTransport?: NativeTransport;
  }
}

export class AtsClient {
  readonly pluginId: string;
  readonly sessionId: string;
  private readonly transport: NativeTransport;
  private readonly pending = new Map<string, Pending>();
  private readonly eventListeners = new Map<AtsEvent, Set<(payload: unknown) => void>>();
  private nextRequest = 1;
  private lastSequence = -1;
  private readonly ready: Promise<unknown>;
  private resolveReady!: (payload: unknown) => void;
  private rejectReady!: (reason: unknown) => void;
  private readyTimeout: number | undefined;

  constructor(options: AtsClientOptions) {
    const transport = options.transport ?? window.atsTransport;
    if (!transport) throw new Error("ATS transport is unavailable");
    this.pluginId = options.pluginId;
    this.sessionId = options.sessionId;
    this.transport = transport;
    this.transport.onmessage = (event) => this.receive(event.data);
    this.ready = new Promise<unknown>((resolve, reject) => {
      this.resolveReady = resolve;
      this.rejectReady = reject;
    });
    this.readyTimeout = window.setTimeout(() => {
      this.rejectReady(new Error("ATS runtime handshake timed out"));
    }, 5_000);
    this.transport.postMessage(JSON.stringify({
      protocol: RPC_PROTOCOL,
      kind: "hello",
      pluginId: this.pluginId,
      sessionId: this.sessionId,
      requestId: "0",
      payload: { supportedProtocols: [RPC_PROTOCOL], features: [] },
    }));
  }

  static fromDocument(transport?: NativeTransport): AtsClient {
    const pluginId = document.querySelector<HTMLMetaElement>('meta[name="ats-plugin-id"]')?.content;
    const sessionId = document.querySelector<HTMLMetaElement>('meta[name="ats-session-id"]')?.content;
    if (!pluginId || !sessionId) throw new Error("ATS bootstrap metadata is unavailable");
    return new AtsClient({ pluginId, sessionId, transport });
  }

  whenReady<T = AtsReadyPayload>(): Promise<T> {
    return this.ready as Promise<T>;
  }

  async request<T = unknown>(method: AtsCapabilityMethod, payload: unknown = {}, deadlineMs = 30_000): Promise<T> {
    await this.ready;
    if (this.pending.size >= 64) return Promise.reject(new Error("Too many pending ATS requests"));
    const requestId = String(this.nextRequest++);
    const message = {
      protocol: RPC_PROTOCOL,
      kind: "request",
      pluginId: this.pluginId,
      sessionId: this.sessionId,
      requestId,
      method,
      payload,
      deadlineMs,
    } as const;
    return new Promise<T>((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        this.pending.delete(requestId);
        this.transport.postMessage(JSON.stringify({
          protocol: RPC_PROTOCOL,
          kind: "cancel",
          pluginId: this.pluginId,
          sessionId: this.sessionId,
          requestId,
        }));
        reject(new Error(`ATS request timed out: ${method}`));
      }, deadlineMs);
      this.pending.set(requestId, { resolve: resolve as (value: unknown) => void, reject, timeout });
      this.transport.postMessage(JSON.stringify(message));
    });
  }

  on(event: AtsEvent, listener: (payload: unknown) => void): () => void {
    const listeners = this.eventListeners.get(event) ?? new Set();
    listeners.add(listener);
    this.eventListeners.set(event, listeners);
    return () => {
      listeners.delete(listener);
      if (listeners.size === 0) this.eventListeners.delete(event);
    };
  }

  async readDataset(datasetId: string): Promise<Uint8Array | null> {
    const opened = await this.request<{
      found: boolean;
      handle?: string;
      size?: number;
    }>("storage.dataset.openRead", { datasetId });
    if (!opened.found || !opened.handle) return null;
    if (!Number.isSafeInteger(opened.size) || (opened.size ?? -1) < 0 || (opened.size ?? 0) > 512 * 1024 * 1024) {
      await this.request("storage.dataset.abort", { handle: opened.handle }).catch(() => undefined);
      throw new Error("ATS returned an invalid Dataset size");
    }
    const output = new Uint8Array(opened.size ?? 0);
    let offset = 0;
    try {
      while (offset < output.length || output.length === 0) {
        const part = await this.request<{ bytes: string; offset: number; eof: boolean }>(
          "storage.dataset.read",
          { handle: opened.handle, offset, maxBytes: 192 * 1024 },
        );
        const bytes = decodeBase64(part.bytes);
        if (part.offset !== offset || offset + bytes.length > output.length) {
          throw new Error("ATS returned an invalid Dataset chunk");
        }
        output.set(bytes, offset);
        offset += bytes.length;
        if (part.eof) break;
        if (bytes.length === 0) throw new Error("ATS returned an empty non-final Dataset chunk");
      }
      if (offset !== output.length) throw new Error("ATS Dataset ended before its declared size");
      return output;
    } finally {
      await this.request("storage.dataset.abort", { handle: opened.handle }).catch(() => undefined);
    }
  }

  async writeDataset(datasetId: string, value: Uint8Array): Promise<unknown> {
    const opened = await this.request<{ handle: string; maxBytes: number }>(
      "storage.dataset.openWrite",
      { datasetId },
    );
    if (value.byteLength > opened.maxBytes) {
      await this.request("storage.dataset.abort", { handle: opened.handle }).catch(() => undefined);
      throw new Error("Dataset exceeds manifest maxBytes");
    }
    try {
      for (let offset = 0; offset < value.byteLength; offset += 128 * 1024) {
        await this.request("storage.dataset.write", {
          handle: opened.handle,
          bytes: encodeBase64(value.subarray(offset, offset + 128 * 1024)),
        });
      }
      return await this.request("storage.dataset.commit", { handle: opened.handle });
    } catch (error) {
      await this.request("storage.dataset.abort", { handle: opened.handle }).catch(() => undefined);
      throw error;
    }
  }

  async readDatasetJson<T>(datasetId: string): Promise<T | null> {
    const bytes = await this.readDataset(datasetId);
    return bytes === null ? null : JSON.parse(new TextDecoder().decode(bytes)) as T;
  }

  writeDatasetJson(datasetId: string, value: unknown): Promise<unknown> {
    return this.writeDataset(datasetId, new TextEncoder().encode(JSON.stringify(value)));
  }

  async getSecret<T>(datasetId: string, key: string): Promise<T | null> {
    const result = await this.request<{ found: boolean; value?: T }>("storage.secret.get", { datasetId, key });
    return result.found ? result.value as T : null;
  }

  setSecret(datasetId: string, key: string, value: unknown): Promise<unknown> {
    return this.request("storage.secret.set", { datasetId, key, value });
  }

  deleteSecret(datasetId: string, key: string): Promise<unknown> {
    return this.request("storage.secret.delete", { datasetId, key });
  }

  close(): void {
    if (this.readyTimeout !== undefined) window.clearTimeout(this.readyTimeout);
    this.rejectReady(new Error("ATS session closed before handshake completed"));
    for (const [requestId, pending] of this.pending) {
      window.clearTimeout(pending.timeout);
      pending.reject(new Error(`ATS session closed: ${requestId}`));
    }
    this.pending.clear();
    this.eventListeners.clear();
    this.transport.onmessage = null;
  }

  private receive(raw: string): void {
    if (new TextEncoder().encode(raw).byteLength > 256 * 1024) return;
    let message: RpcReady | RpcResponse | RpcEvent;
    try {
      message = JSON.parse(raw) as RpcReady | RpcResponse | RpcEvent;
    } catch {
      return;
    }
    if (
      message.protocol !== RPC_PROTOCOL
      || message.pluginId !== this.pluginId
      || message.sessionId !== this.sessionId
    ) return;

    if (message.kind === "ready" && message.requestId === "0") {
      if (this.readyTimeout !== undefined) window.clearTimeout(this.readyTimeout);
      this.readyTimeout = undefined;
      this.applyTheme(message.payload);
      this.resolveReady(message.payload);
      return;
    }

    if (message.kind === "response") {
      const pending = this.pending.get(message.requestId);
      if (!pending) return;
      this.pending.delete(message.requestId);
      window.clearTimeout(pending.timeout);
      if (message.ok) pending.resolve(message.result);
      else pending.reject(new AtsRpcError(message.error ?? {
        code: "INTERNAL",
        message: "ATS returned an invalid error response",
        retryable: false,
      }));
      return;
    }
    if (message.kind === "event" && message.sequence > this.lastSequence) {
      this.lastSequence = message.sequence;
      if (message.event === "app.themeChanged") this.applyTheme(message.payload);
      for (const listener of this.eventListeners.get(message.event) ?? []) listener(message.payload);
    }
  }

  private applyTheme(payload: unknown): void {
    if (!payload || typeof payload !== "object" || !("css" in payload)) return;
    const css = (payload as { css?: unknown }).css;
    if (typeof css !== "string") return;
    let style = document.querySelector<HTMLStyleElement>('style[data-ats-runtime-theme]');
    if (!style) {
      style = document.createElement("style");
      style.dataset.atsRuntimeTheme = "true";
      document.head.append(style);
    }
    style.textContent = css;
  }
}

function encodeBase64(value: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < value.length; offset += 0x8000) {
    binary += String.fromCharCode(...value.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, character => character.charCodeAt(0));
}
