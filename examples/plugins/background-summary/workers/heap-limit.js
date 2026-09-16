globalThis.atsWorkerMain = async function () {
  const values = [];
  while (true) values.push('x'.repeat(65536));
};
