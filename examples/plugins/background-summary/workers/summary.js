globalThis.atsWorkerMain = async function (_input) {
  return {
    completedAt: Date.now(),
    itemsProcessed: 0,
    summary: '后台任务运行完成'
  };
};
