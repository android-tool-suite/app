globalThis.atsWorkerMain = async function () {
  const error = new Error('用于验证重试上限的临时失败');
  error.code = 'TEMPORARY';
  error.retryable = true;
  throw error;
};
