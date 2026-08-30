globalThis.atsWorkerMain = async (input) => {
  if (input?.kind !== "capability" || input?.method !== "sample.echo.call") {
    const error = new Error("Unsupported capability call");
    error.code = "INVALID_REQUEST";
    throw error;
  }
  return { value: String(input?.payload?.value ?? "") };
};
