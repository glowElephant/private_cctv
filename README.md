# Private CCTV

남는 Android 기기로 CCTV를 만들어보자

## 구조

```
[Android 카메라 앱] ──WebRTC──→ [Node.js 서버] ──WebRTC──→ [웹 뷰어]
```

## 구성요소

| 폴더 | 설명 |
|------|------|
| `/server` | Node.js 시그널링 서버 |
| `/android` | 카메라 스트리밍 앱 |

## 서버 설치

```bash
cd server
npm install
npm run cert
npm start
```

접속: `https://서버IP:8443`

## Android 앱 빌드

Android Studio에서 `/android` 폴더 열고 빌드

## 라이선스

MIT License
