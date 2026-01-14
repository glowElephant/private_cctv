const https = require('https');
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const config = require('./config.json');

// 인증서 확인
const certPath = path.join(__dirname, 'certs', 'server.crt');
const keyPath = path.join(__dirname, 'certs', 'server.key');

if (!fs.existsSync(certPath) || !fs.existsSync(keyPath)) {
  console.log('인증서가 없습니다. 먼저 npm run cert 를 실행하세요.');
  process.exit(1);
}

// HTTPS 서버 생성
const server = https.createServer({
  cert: fs.readFileSync(certPath),
  key: fs.readFileSync(keyPath)
}, (req, res) => {
  // 정적 파일 서빙
  if (req.url === '/' || req.url === '/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(fs.readFileSync(path.join(__dirname, 'public', 'viewer.html')));
  } else if (req.url === '/config') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ viewerToken: config.tokens.viewer }));
  } else {
    res.writeHead(404);
    res.end('Not Found');
  }
});

// WebSocket 서버
const wss = new WebSocket.Server({ server });

// 상태 관리
let camera = null;
const viewers = new Map();

function log(msg) {
  console.log(`[${new Date().toLocaleTimeString()}] ${msg}`);
}

function broadcast(data, excludeId = null) {
  viewers.forEach((viewer, id) => {
    if (id !== excludeId && viewer.ws.readyState === WebSocket.OPEN) {
      viewer.ws.send(JSON.stringify(data));
    }
  });
}

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `https://localhost:${config.port}`);
  const role = url.searchParams.get('role');
  const token = url.searchParams.get('token');

  // 토큰 검증
  if (role === 'camera' && token !== config.tokens.camera) {
    log('카메라 인증 실패');
    ws.close(4001, 'Invalid token');
    return;
  }
  if (role === 'viewer' && token !== config.tokens.viewer) {
    log('뷰어 인증 실패');
    ws.close(4001, 'Invalid token');
    return;
  }

  if (role === 'camera') {
    // 카메라 연결
    if (camera) {
      log('기존 카메라 연결 종료');
      camera.close();
    }
    camera = ws;
    log('카메라 연결됨');

    // 기존 뷰어들에게 카메라 연결 알림
    broadcast({ type: 'camera-connected' });

    ws.on('message', (data) => {
      try {
        const msg = JSON.parse(data);

        if (msg.type === 'offer' || msg.type === 'answer' || msg.type === 'ice-candidate') {
          // 특정 뷰어에게 전달
          if (msg.targetId && viewers.has(msg.targetId)) {
            viewers.get(msg.targetId).ws.send(JSON.stringify({
              ...msg,
              fromCamera: true
            }));
          }
        }
      } catch (e) {
        log('카메라 메시지 파싱 에러: ' + e.message);
      }
    });

    ws.on('close', () => {
      log('카메라 연결 종료');
      camera = null;
      broadcast({ type: 'camera-disconnected' });
    });

  } else if (role === 'viewer') {
    // 뷰어 연결
    if (viewers.size >= config.maxViewers) {
      log('최대 뷰어 수 초과');
      ws.close(4002, 'Max viewers reached');
      return;
    }

    const viewerId = Date.now().toString();
    viewers.set(viewerId, { ws, id: viewerId });
    log(`뷰어 연결됨 (ID: ${viewerId}, 총 ${viewers.size}명)`);

    // 뷰어에게 ID 전송
    ws.send(JSON.stringify({ type: 'your-id', id: viewerId }));

    // 카메라 상태 전송
    if (camera && camera.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'camera-connected' }));
    }

    ws.on('message', (data) => {
      try {
        const msg = JSON.parse(data);
        msg.viewerId = viewerId;

        if (msg.type === 'request-offer') {
          // 카메라에게 offer 요청
          if (camera && camera.readyState === WebSocket.OPEN) {
            camera.send(JSON.stringify({
              type: 'viewer-joined',
              viewerId: viewerId
            }));
          }
        } else if (msg.type === 'answer' || msg.type === 'ice-candidate') {
          // 카메라에게 전달
          if (camera && camera.readyState === WebSocket.OPEN) {
            camera.send(JSON.stringify(msg));
          }
        }
      } catch (e) {
        log('뷰어 메시지 파싱 에러: ' + e.message);
      }
    });

    ws.on('close', () => {
      viewers.delete(viewerId);
      log(`뷰어 연결 종료 (ID: ${viewerId}, 남은 ${viewers.size}명)`);

      // 카메라에게 뷰어 종료 알림
      if (camera && camera.readyState === WebSocket.OPEN) {
        camera.send(JSON.stringify({
          type: 'viewer-left',
          viewerId: viewerId
        }));
      }
    });

  } else {
    ws.close(4000, 'Invalid role');
  }
});

server.listen(config.port, '0.0.0.0', () => {
  log(`CCTV 서버 시작: https://localhost:${config.port}`);
  log(`외부 접속: https://192.168.1.3:${config.port}`);
  log(`카메라 토큰: ${config.tokens.camera}`);
  log(`뷰어 토큰: ${config.tokens.viewer}`);
});
