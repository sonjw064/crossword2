import { FrameParser, backoffDelay, encodeFrame } from './stomp-frames.js';

const HEARTBEAT_MS = 10000;
const SILENCE_LIMIT_MS = 35000; // 서버 하트비트(10초)가 이만큼 끊기면 연결이 죽은 것으로 본다

/**
 * 같은 출처의 /ws 로 연결하는 최소 STOMP 클라이언트. 끊기면 지수 백오프로 다시 연결하고(토큰은 매번 새로 받는다),
 * 구독은 다시 연결할 때 자동으로 복구한다. 상태: 'connecting' | 'connected' | 'disconnected'.
 * getToken: () => Promise<string|null>, onAuthError: 연결 거부(ERROR 프레임)를 받으면 호출(토큰 갱신용).
 */
export class StompClient {
  constructor({ url, getToken, onState, onReconnect, onAuthError, WebSocketImpl = WebSocket }) {
    this.url = url;
    this.getToken = getToken;
    this.onState = onState ?? (() => {});
    this.onReconnect = onReconnect ?? (() => {});
    this.onAuthError = onAuthError ?? (async () => {});
    this.WebSocketImpl = WebSocketImpl;
    this.subs = new Map(); // id -> { destination, handler }
    this.nextId = 0;
    this.attempt = 0;
    this.closedByUser = false;
    this.everConnected = false;
    this.ready = false;
    this.socket = null;
    this.timers = [];
  }

  async connect() {
    this.closedByUser = false;
    this.#setState('connecting');
    let token = null;
    try {
      token = await this.getToken();
    } catch {
      // 토큰을 못 받으면 연결 시도에서 거부되고 재시도한다
    }
    if (this.closedByUser) return;
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new this.WebSocketImpl(this.url.startsWith('/') ? `${scheme}://${location.host}${this.url}` : this.url);
    this.socket = socket;
    const parser = new FrameParser();
    let lastSeen = Date.now();
    socket.onopen = () => {
      const headers = { 'accept-version': '1.2', 'heart-beat': `${HEARTBEAT_MS},${HEARTBEAT_MS}` };
      if (token) headers.Authorization = `Bearer ${token}`;
      socket.send(encodeFrame('CONNECT', headers));
    };
    socket.onmessage = (event) => {
      lastSeen = Date.now();
      if (typeof event.data !== 'string') return;
      for (const frame of parser.push(event.data)) this.#onFrame(socket, frame);
    };
    socket.onclose = () => this.#onClosed(socket);
    socket.onerror = () => {}; // 곧 close 가 따라온다
    this.timers.push(setInterval(() => {
      if (socket.readyState !== 1) return;
      if (Date.now() - lastSeen > SILENCE_LIMIT_MS) socket.close();
      else socket.send('\n');
    }, HEARTBEAT_MS));
  }

  subscribe(destination, handler) {
    const id = `sub-${this.nextId++}`;
    this.subs.set(id, { destination, handler });
    if (this.connected) this.#sendSubscribe(id, destination);
    return () => {
      if (!this.subs.delete(id)) return;
      if (this.connected) this.socket.send(encodeFrame('UNSUBSCRIBE', { id }));
    };
  }

  /** 연결돼 있을 때만 보낸다. 보냈으면 true. */
  send(destination, payload) {
    if (!this.connected) return false;
    const body = payload === undefined ? '' : JSON.stringify(payload);
    this.socket.send(encodeFrame('SEND', { destination, 'content-type': 'application/json' }, body));
    return true;
  }

  get connected() {
    return this.socket?.readyState === 1 && this.ready;
  }

  close() {
    this.closedByUser = true;
    this.#clearTimers();
    const socket = this.socket;
    this.socket = null;
    this.ready = false;
    if (socket) {
      socket.onclose = null;
      try {
        if (socket.readyState === 1) socket.send(encodeFrame('DISCONNECT'));
        socket.close();
      } catch {
        // 이미 닫혔다
      }
    }
  }

  #onFrame(socket, frame) {
    if (socket !== this.socket) return;
    if (frame.command === 'CONNECTED') {
      this.ready = true;
      this.attempt = 0;
      for (const [id, sub] of this.subs) this.#sendSubscribe(id, sub.destination);
      const again = this.everConnected;
      this.everConnected = true;
      this.#setState('connected');
      if (again) this.onReconnect();
    } else if (frame.command === 'MESSAGE') {
      const sub = this.subs.get(frame.headers.subscription);
      if (!sub) return;
      let data = null;
      try {
        data = frame.body ? JSON.parse(frame.body) : null;
      } catch {
        return; // 형식이 깨진 메시지는 무시한다
      }
      sub.handler(data);
    } else if (frame.command === 'ERROR') {
      // 서버가 연결을 거부했다(토큰 만료 등). 토큰을 갱신하고 다시 시도한다.
      this.onAuthError();
      socket.close();
    }
  }

  #onClosed(socket) {
    if (socket !== this.socket) return;
    this.socket = null;
    this.ready = false;
    this.#clearTimers();
    if (this.closedByUser) return;
    this.#setState('disconnected');
    const delay = backoffDelay(this.attempt++, Math.random());
    this.timers.push(setTimeout(() => this.connect(), delay));
  }

  #sendSubscribe(id, destination) {
    this.socket.send(encodeFrame('SUBSCRIBE', { id, destination }));
  }

  #clearTimers() {
    for (const t of this.timers) {
      clearTimeout(t);
      clearInterval(t);
    }
    this.timers = [];
  }

  #setState(state) {
    if (this.state === state) return;
    this.state = state;
    this.onState(state);
  }
}
