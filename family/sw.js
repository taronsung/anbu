// 가족 화면의 알림용 서비스 워커.
// 하는 일은 하나다: 화면이 띄운 알림을 누르면 가족 화면을 앞으로 가져온다.
// 서버가 밀어 보내는 알림(push)은 받지 않는다. 가족 화면이 열려 있는 동안에만 알림이 뜬다.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      for (const c of list) if ('focus' in c) return c.focus();
      return self.clients.openWindow('./');
    })
  );
});
