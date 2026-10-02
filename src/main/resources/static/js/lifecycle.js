// 화면이 사라질 때 정리해야 하는 자원(이벤트 리스너, object URL)을 다루는 순수 도우미. (node --test 로 테스트: src/test/js)

/** 이벤트 리스너를 달고, 한 번만 제거하는 함수를 돌려준다. */
export function listen(target, type, handler) {
  target.addEventListener(type, handler);
  let removed = false;
  return () => {
    if (removed) return;
    removed = true;
    target.removeEventListener(type, handler);
  };
}

/**
 * 한 번에 하나만 활성화되는 자원 칸. 새로 설정하면 이전 것을 먼저 정리하므로,
 * 같은 화면에서 시작/종료를 반복해도 리스너가 누적되지 않는다.
 */
export function singleSlot() {
  let dispose = null;
  return {
    set(next) {
      this.dispose();
      dispose = next;
    },
    dispose() {
      const current = dispose;
      dispose = null;
      current?.();
    },
    get active() {
      return dispose !== null;
    },
  };
}

/** createObjectURL로 만든 주소를 기억했다가 한꺼번에(또는 하나씩) revoke한다. */
export function createObjectUrlTracker(urlApi = URL) {
  const urls = new Set();
  return {
    create(blob) {
      const url = urlApi.createObjectURL(blob);
      urls.add(url);
      return url;
    },
    revoke(url) {
      if (urls.delete(url)) urlApi.revokeObjectURL(url);
    },
    revokeAll() {
      for (const url of urls) urlApi.revokeObjectURL(url);
      urls.clear();
    },
    get size() {
      return urls.size;
    },
  };
}
