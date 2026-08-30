document.getElementById('session').addEventListener('click', async () => {
  const output = document.getElementById('output');
  output.textContent = '正在读取…';
  try {
    const pluginId = document.querySelector('meta[name="ats-plugin-id"]')?.content;
    const sessionId = document.querySelector('meta[name="ats-session-id"]')?.content;
    const transport = window.atsTransport;
    if (!pluginId || !sessionId || !transport) throw new Error('ATS transport 不可用');
    const requestId = 'sample-' + Date.now();
    const result = await new Promise((resolve, reject) => {
      const previous = transport.onmessage;
      const timeout = setTimeout(() => reject(new Error('调用超时')), 5000);
      transport.onmessage = event => {
        const message = JSON.parse(event.data);
        if (message.kind === 'ready') {
          transport.postMessage(JSON.stringify({protocol:'2.0',kind:'request',pluginId,sessionId,requestId,method:'app.getSession',payload:{},deadlineMs:5000}));
        } else if (message.kind === 'response' && message.requestId === requestId) {
          clearTimeout(timeout); transport.onmessage = previous; message.ok ? resolve(message.result) : reject(new Error(message.error?.message || '调用失败'));
        }
      };
      transport.postMessage(JSON.stringify({protocol:'2.0',kind:'hello',pluginId,sessionId,requestId:'0',payload:{supportedProtocols:['2.0'],features:[]}}));
    });
    output.textContent = JSON.stringify(result, null, 2);
  } catch (error) { output.textContent = error.message; }
});
